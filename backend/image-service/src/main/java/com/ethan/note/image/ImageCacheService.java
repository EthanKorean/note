package com.ethan.note.image;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 이미지 한 장을 모드에 따라 내려 준다. 응답 시간과 적중 여부를 호출자가 잰다. */
@Service
public class ImageCacheService {

    private static final Logger log = LoggerFactory.getLogger(ImageCacheService.class);
    private static final DefaultRedisScript<Long> COUNT_HIT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end return n",
            Long.class);

    /** @param hit Redis 에서 찾았으면 true */
    public record Fetch(byte[] bytes, boolean hit) {
    }

    private final ImageStore store;
    private final RedisTemplate<String, byte[]> redis;
    private final StringRedisTemplate counters;
    private final AtomicLong originReads = new AtomicLong();
    private final AtomicLong cacheWriteFailures = new AtomicLong();
    private volatile long lastWarnAt;
    private volatile String hotNs = "0";
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inflight = new ConcurrentHashMap<>();

    public ImageCacheService(ImageStore store, RedisTemplate<String, byte[]> redis, StringRedisTemplate counters) {
        this.store = store;
        this.redis = redis;
        this.counters = counters;
    }

    /** 상품별 이미지 버전(상품 메타데이터에 들어 있는 값이라고 보면 된다). 버전 방식 무효화에서만 올라간다. */
    private final ConcurrentHashMap<Integer, Integer> versions = new ConcurrentHashMap<>();

    private String key(int id, ImageSize size) {
        return "img:" + id + ":v" + versions.getOrDefault(id, 0) + ":" + size.key;
    }

    /** 이미지가 바뀌었으니 이 상품의 캐시를 지운다(삭제 방식). 지운 키 수를 돌려준다. */
    public long purge(int id) {
        List<String> keys = java.util.Arrays.stream(ImageSize.values()).map(s -> key(id, s)).toList();
        Long n = redis.delete(keys);
        return n == null ? 0 : n;
    }

    /** 이미지가 바뀌었으니 버전을 올린다(버전 방식). 옛 키는 그대로 두고 아무도 읽지 않게 되어 TTL 로 사라진다. */
    public void bumpVersion(int id) {
        versions.merge(id, 1, Integer::sum);
    }

    public void resetVersions() {
        versions.clear();
    }

    /** 실험마다 새 이름공간을 써서 이전 실험의 카운터가 섞이지 않게 한다. */
    public void newRun() {
        hotNs = Long.toString(System.currentTimeMillis(), 36);
        cacheWriteFailures.set(0);
    }

    public long originReads() {
        return originReads.get();
    }

    /** 실험용: 원본 읽기 횟수와 "자주 본다" 카운터를 이번 실험 이름공간에 센다. */
    public Fetch fetch(int id, ImageSize size, BenchParams p) throws IOException {
        return fetch(id, size, p, true);
    }

    /** 서비스용(/api/image/{id}): 실험 통계와 카운터를 건드리지 않고, Redis 가 안 되면 미리 만든 파일로 대신한다. */
    public Fetch fetchForServing(int id, ImageSize size, String mode) throws IOException {
        BenchParams p = BenchParams.forServing(mode);
        try {
            return fetch(id, size, p, false);
        } catch (IOException | RuntimeException e) {
            if (p.cacheMode() != CacheMode.REDIS) {
                throw e;
            }
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > 60_000) {   // Redis 장애가 "캐시가 차갑다"로 보이지 않게 1분에 한 번 남긴다
                lastWarnAt = now;
                log.warn("Redis 조회 실패, 미리 만든 파일로 대신 응답: {}", e.toString());
            }
            return new Fetch(store.readPrebuilt(id, size), false);
        }
    }

    private Fetch fetch(int id, ImageSize size, BenchParams p, boolean bench) throws IOException {
        return switch (p.cacheMode()) {
            case NOCACHE -> new Fetch(readOrigin(id, size, p, true, bench), false);
            case PREBUILT -> new Fetch(readOrigin(id, size, p, false, bench), false);
            case REDIS -> fetchRedis(id, size, p, bench);
        };
    }

    private Fetch fetchRedis(int id, ImageSize size, BenchParams p, boolean bench) throws IOException {
        String key = key(id, size);
        byte[] cached = redis.opsForValue().get(key);
        if (cached != null) {
            return new Fetch(cached, true);
        }
        if (!p.stampedeGuard()) {
            return new Fetch(loadAndMaybeCache(key, id, size, p, bench), false);
        }
        // 같은 키를 동시에 읽으려는 요청은 하나만 원본으로 가고 나머지는 그 결과를 기다린다.
        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inflight.putIfAbsent(key, mine);
        if (existing != null) {
            try {
                return new Fetch(existing.join(), false);
            } catch (CompletionException e) {
                throw new IOException(e.getCause());
            }
        }
        try {
            // 리더가 되기 직전에 다른 리더가 막 캐시에 올렸을 수 있으니 한 번 더 확인한다.
            byte[] again = redis.opsForValue().get(key);
            if (again != null) {
                mine.complete(again);
                return new Fetch(again, true);
            }
            byte[] bytes = loadAndMaybeCache(key, id, size, p, bench);
            mine.complete(bytes);
            return new Fetch(bytes, false);
        } catch (Throwable e) {
            // Error(OOM 등)도 기다리는 요청에 전달해서 영원히 대기하지 않게 한다.
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key);
        }
    }

    /** 원본(미리 만든 파일)을 읽고, 최근에 자주 찾던 상품이면 Redis 에 올린다. */
    private byte[] loadAndMaybeCache(String key, int id, ImageSize size, BenchParams p, boolean bench) throws IOException {
        byte[] bytes = readOrigin(id, size, p, false, bench);
        try {
            // INCR 과 EXPIRE 를 한 번에(원자적으로) 실행해서 만료 없는 카운터가 남지 않게 한다.
            Long n = counters.execute(COUNT_HIT, List.of("hot:" + (bench ? hotNs : "srv") + ":" + id + ":" + size.key), String.valueOf(p.windowSec()));
            if (n != null && n >= p.hotThreshold()) {
                redis.opsForValue().set(key, bytes, Duration.ofSeconds(p.ttlSec()));
            }
        } catch (RuntimeException e) {
            // 메모리가 가득 찬 noeviction 등으로 쓰기가 거절돼도 응답은 줘야 한다(캐시에 못 올린 미스).
            cacheWriteFailures.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > 60_000) {   // 같은 경고가 로그를 채우지 않도록 1분에 한 번만
                lastWarnAt = now;
                log.warn("Redis 쓰기 실패(누적 {}회): {}", cacheWriteFailures.get(), e.getMessage());
            }
        }
        return bytes;
    }

    private byte[] readOrigin(int id, ImageSize size, BenchParams p, boolean resize, boolean bench) throws IOException {
        if (bench) {
            originReads.incrementAndGet();
        }
        if (p.originLatencyMs() > 0) {
            try {
                Thread.sleep(p.originLatencyMs());   // 원격 스토리지 지연을 흉내 낸다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("중단됨", e);
            }
        }
        return resize ? store.resizeFromOriginal(id, size) : store.readPrebuilt(id, size);
    }
}
