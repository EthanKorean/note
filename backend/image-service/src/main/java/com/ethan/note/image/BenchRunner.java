package com.ethan.note.image;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

/** 조건대로 요청을 쏘고 응답 시간, 적중률, 원본 읽기 횟수를 잰다. 한 번에 하나만 실행한다. */
@Service
public class BenchRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchRunner.class);
    private static final int BUCKETS = 20;

    private final ImageCacheService service;
    private final ImageStore store;
    private final RedisTemplate<String, byte[]> redis;
    private final WorkGuard guard;
    private final ImageEvents events;

    public BenchRunner(ImageCacheService service, ImageStore store, RedisTemplate<String, byte[]> redis, WorkGuard guard, ImageEvents events) {
        this.guard = guard;
        this.events = events;
        this.service = service;
        this.store = store;
        this.redis = redis;
    }

    public boolean isRunning() {
        return guard.isBusy();
    }

    public BenchResult run(BenchParams p) throws Exception {
        guard.acquire("캐시 실험");
        SavedRedisSettings prevConfig = null;
        try {
            if (store.count() < p.products()) {
                throw new IllegalArgumentException("이미지가 " + store.count() + "개뿐입니다. 먼저 이미지를 " + p.products() + "개 이상 만드세요.");
            }
            int missing = store.firstIncomplete(p.products());
            if (missing != 0) {
                throw new IllegalArgumentException("상품 " + missing + "의 이미지 파일이 빠져 있습니다. 이미지를 다시 만드세요.");
            }
            // Redis 는 redis 방식에서만 쓴다. 나머지 방식은 Redis 가 꺼져 있어도 돌아야 한다.
            if (p.cacheMode() == CacheMode.REDIS) {
                prevConfig = readSavedRedisSettings();
                configureRedis(p);
                events.info(p.mode(), "Redis 설정: 메모리 " + p.maxMemoryMb() + "MB, 제거 정책 " + p.evictionPolicy());
                if (p.resetCache()) {
                    events.info(p.mode(), "캐시 " + clearKeys() + "개 키 비움");
                    // 캐시를 비우는 실험은 "자주 본다" 카운터도 새 이름공간으로 시작한다.
                    // 비우지 않는 실험은 이전 기록을 이어서 쓴다(따뜻한 캐시 비교용).
                    service.newRun();
                }
            }
            events.info(p.mode(), String.format("요청 %,d건 / 스레드 %d / 인기 %d개(%d%%) / 원본 지연 %dms",
                    p.requests(), p.threads(), p.hotSetSize(), Math.round(p.hotRatio() * 100), p.originLatencyMs()));
            BenchResult r = execute(p);
            events.success(p.mode(), String.format("완료: 평균 %.2fms · p95 %.2fms · 적중률 %.1f%% · 원본 읽기 %,d회",
                    r.avgMs(), r.p95Ms(), r.hitRatio() * 100, r.originReads()));
            return r;
        } catch (Exception e) {
            events.error(p.mode(), "실패: " + PathMask.scrub(e.getMessage())); // 웹소켓으로 나가므로 경로를 가린다
            throw e;
        } finally {
            // 실험이 바꾼 maxmemory/정책은 되돌려서 다른 사용(서비스용 주소 등)에 남지 않게 한다.
            restoreSavedRedisSettings(prevConfig);
            guard.release();
        }
    }

    private BenchResult execute(BenchParams p) throws Exception {
        boolean useRedis = p.cacheMode() == CacheMode.REDIS;
        long evictedBefore = useRedis ? redisStat("stats", "evicted_keys") : 0;
        long originBefore = service.originReads();
        int n = p.requests();
        long[] lat = new long[n];
        AtomicLongArray bucketTotal = new AtomicLongArray(BUCKETS);
        AtomicLongArray bucketHit = new AtomicLongArray(BUCKETS);
        AtomicLongArray bucketNanos = new AtomicLongArray(BUCKETS);
        AtomicLong hits = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        AtomicInteger next = new AtomicInteger();
        AtomicBoolean abort = new AtomicBoolean();
        int progressStep = Math.max(1, n / 10);

        ExecutorService pool = Executors.newFixedThreadPool(p.threads());
        long t0 = System.nanoTime();
        try {
            ExecutorCompletionService<Void> done = new ExecutorCompletionService<>(pool);
            for (int t = 0; t < p.threads(); t++) {
                done.submit(() -> {
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    int i;
                    while (!abort.get() && (i = next.getAndIncrement()) < n) {
                        int id = pickProduct(p, i, r);
                        ImageSize size = pickSize(r);
                        long s = System.nanoTime();
                        ImageCacheService.Fetch f = service.fetch(id, size, p);
                        long took = System.nanoTime() - s;
                        lat[i] = took;
                        int b = (int) ((long) i * BUCKETS / n);
                        if ((i + 1) % progressStep == 0 && i + 1 < n) {
                            events.info(p.mode(), String.format("진행 %,d/%,d건 (%d%%) · 적중 %,d", i + 1, n, (i + 1) * 100L / n, hits.get()));
                        }
                        bucketTotal.incrementAndGet(b);
                        bucketNanos.addAndGet(b, took);
                        bytes.addAndGet(f.bytes().length);
                        if (f.hit()) {
                            hits.incrementAndGet();
                            bucketHit.incrementAndGet(b);
                        }
                    }
                    return null;
                });
            }
            // 끝난 순서대로 확인해서, 한 스레드가 실패하면 바로 알아채고 나머지를 멈춘다.
            for (int t = 0; t < p.threads(); t++) {
                try {
                    done.take().get();
                } catch (ExecutionException e) {
                    abort.set(true);
                    throw e.getCause() instanceof Exception ex ? ex : e;
                }
            }
        } finally {
            abort.set(true);
            pool.shutdownNow();
            // 남은 작업이 끝난 뒤에 실행 중 표시를 풀어야 다음 실험과 겹치지 않는다.
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("실험 스레드가 30초 안에 끝나지 않았습니다.");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        long elapsedNanos = System.nanoTime() - t0;

        long[] sorted = lat.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (long v : lat) {
            sum += v;
        }
        double[] tHit = new double[BUCKETS];
        double[] tLat = new double[BUCKETS];
        for (int b = 0; b < BUCKETS; b++) {
            long tot = bucketTotal.get(b);
            tHit[b] = tot == 0 ? 0 : (double) bucketHit.get(b) / tot;
            tLat[b] = tot == 0 ? 0 : bucketNanos.get(b) / 1e6 / tot;
        }
        long evicted = useRedis ? redisStat("stats", "evicted_keys") - evictedBefore : 0;
        double usedMb = useRedis ? redisStat("memory", "used_memory") / 1024.0 / 1024.0 : 0;
        double sec = elapsedNanos / 1e9;
        log.info("[{}] {}건 {} ms, 적중 {}, 원본 읽기 {}", p.mode(), n, elapsedNanos / 1_000_000, hits.get(),
                service.originReads() - originBefore);
        return new BenchResult(p, elapsedNanos / 1_000_000, n / sec, sum / n / 1e6,
                pct(sorted, 0.50), pct(sorted, 0.95), pct(sorted, 0.99),
                (double) hits.get() / n, service.originReads() - originBefore, bytes.get(), evicted, usedMb, tHit, tLat);
    }

    /** 인기 상품에 hotRatio 만큼 몰리고, rotateEvery 요청마다 인기 집합이 통째로 바뀐다. */
    static int pickProduct(BenchParams p, int requestIndex, ThreadLocalRandom r) {
        if (r.nextDouble() < p.hotRatio()) {
            // 교체 주기마다 인기 구간이 hotSetSize 만큼 옆으로 옮겨 가므로 바로 앞뒤 인기 집합은 겹치지 않는다
            // (전체 상품 수가 인기 상품 수의 2배 이상일 때).
            long epoch = p.rotateEvery() > 0 ? requestIndex / p.rotateEvery() : 0;
            int k = r.nextInt(p.hotSetSize());
            return (int) ((epoch * p.hotSetSize() + k) % p.products()) + 1;
        }
        return r.nextInt(p.products()) + 1;
    }

    /** 웹 40%, 앱 40%, 썸네일 20%. */
    static ImageSize pickSize(ThreadLocalRandom r) {
        int v = r.nextInt(100);
        return v < 40 ? ImageSize.WEB : v < 80 ? ImageSize.APP : ImageSize.THUMB;
    }

    private static double pct(long[] sorted, double q) {
        int idx = Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1);
        return sorted[Math.max(0, idx)] / 1e6;
    }

    /** 실험 전 Redis 설정(원복용). */
    private record SavedRedisSettings(String maxmemory, String policy) {
    }

    private SavedRedisSettings readSavedRedisSettings() {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<SavedRedisSettings>) c -> {
            Properties mem = c.serverCommands().getConfig("maxmemory");
            Properties pol = c.serverCommands().getConfig("maxmemory-policy");
            return new SavedRedisSettings(mem == null ? null : mem.getProperty("maxmemory"),
                    pol == null ? null : pol.getProperty("maxmemory-policy"));
        });
    }

    private void restoreSavedRedisSettings(SavedRedisSettings prev) {
        if (prev == null) {
            return;
        }
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
                if (prev.maxmemory() != null) {
                    c.serverCommands().setConfig("maxmemory", prev.maxmemory());
                }
                if (prev.policy() != null) {
                    c.serverCommands().setConfig("maxmemory-policy", prev.policy());
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Redis 설정 원복 실패: {}", e.getMessage());
        }
    }

    private void configureRedis(BenchParams p) {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
            c.serverCommands().setConfig("maxmemory", (p.maxMemoryMb() * 1024L * 1024L) + "");
            c.serverCommands().setConfig("maxmemory-policy", p.evictionPolicy());
            return null;
        });
    }

    /** 실험이 돌고 있지 않을 때만 캐시를 비운다(실험 도중에 지우면 측정이 깨진다). */
    public long clearCache() {
        guard.acquire("캐시 비우기");
        try {
            return clearKeys();
        } finally {
            guard.release();
        }
    }

    /** 이 실험이 쓰는 키(img:*, hot:*)만 지운다. 다른 용도의 키는 건드리지 않는다. */
    public long clearKeys() {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) c -> {
            long deleted = 0;
            for (String pattern : new String[] {"img:*", "hot:*"}) {
                try (Cursor<byte[]> cur = c.keyCommands().scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
                    List<byte[]> batch = new ArrayList<>();
                    while (cur.hasNext()) {
                        batch.add(cur.next());
                        if (batch.size() >= 500) {
                            deleted += del(c, batch);
                        }
                    }
                    deleted += del(c, batch);
                }
            }
            return deleted;
        });
    }

    /** 패턴에 맞는 키 개수(SCAN). */
    public long countKeys(String pattern) {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) c -> {
            long n = 0;
            try (Cursor<byte[]> cur = c.keyCommands().scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
                while (cur.hasNext()) {
                    cur.next();
                    n++;
                }
            }
            return n;
        });
    }

    private static long del(RedisConnection c, List<byte[]> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        Long d = c.keyCommands().del(keys.toArray(new byte[0][]));
        keys.clear();
        return d == null ? 0 : d;
    }

    private long redisStat(String section, String field) {
        Properties info = redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>) c -> c.serverCommands().info(section));
        if (info == null || info.getProperty(field) == null) {
            return 0;
        }
        return Long.parseLong(info.getProperty(field).trim());
    }
}
