package com.ethan.note.image;

import java.util.Arrays;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 이미지 교체 실험: 캐시에 올려 둔 이미지를 새 그림으로 바꾸고, 무효화 방식에 따라
 * 바뀐 뒤에도 옛 이미지가 얼마나 나가는지 센다.
 */
@Service
public class ReplaceRunner {

    private static final Set<String> STRATEGIES = Set.of("none", "purge", "version");

    private final ImageCacheService service;
    private final BenchRunner runner;
    private final ImageStore store;
    private final WorkGuard guard;
    private final ImageEvents events;

    public ReplaceRunner(ImageCacheService service, BenchRunner runner, ImageStore store, WorkGuard guard, ImageEvents events) {
        this.service = service;
        this.runner = runner;
        this.store = store;
        this.guard = guard;
        this.events = events;
    }

    public ReplaceResult run(String strategy, int items, int ttlSec) throws Exception {
        if (!STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("strategy는 none | purge | version");
        }
        if (items < 1 || items > 1000) {
            throw new IllegalArgumentException("items는 1~1000");
        }
        if (ttlSec < 1 || ttlSec > 86_400) {
            throw new IllegalArgumentException("ttlSec는 1~86400");
        }
        if (store.count() < items || store.firstIncomplete(items) != 0) {
            throw new IllegalArgumentException("이미지가 " + items + "개 이상 필요합니다. 먼저 이미지를 만드세요.");
        }
        guard.acquire("이미지 교체 실험");
        try {
            BenchParams p = BenchParams.forServing("redis", ttlSec);
            ImageSize[] sizes = ImageSize.values();
            events.info(strategy, "시작: 상품 " + items + "개, TTL " + ttlSec + "초");
            runner.clearKeys();
            service.resetVersions();
            service.newRun();

            // 1) 캐시에 올린다(문턱 1이라 첫 요청부터 올라간다)
            for (int id = 1; id <= items; id++) {
                for (ImageSize s : sizes) {
                    service.fetch(id, s, p);
                }
            }
            events.info(strategy, "캐시에 올림: Redis 키 " + runner.countKeys("img:*") + "개");

            // 2) 이미지를 교체하고 방식대로 무효화한다
            long invalidateNanos = 0;
            long deleted = 0;
            for (int id = 1; id <= items; id++) {
                store.replace(id);
                long t = System.nanoTime();
                switch (strategy) {
                    case "purge" -> deleted += service.purge(id);
                    case "version" -> service.bumpVersion(id);
                    default -> { }
                }
                invalidateNanos += System.nanoTime() - t;
            }
            events.info(strategy, "이미지 " + items + "개 교체, 무효화 " + String.format("%.2f", invalidateNanos / 1e6) + "ms");

            // 3) 다시 요청해서 옛 이미지가 나가는지 확인한다
            int requests = 0;
            int stale = 0;
            for (int id = 1; id <= items; id++) {
                for (ImageSize s : sizes) {
                    byte[] got = service.fetch(id, s, p).bytes();
                    requests++;
                    if (!Arrays.equals(got, store.readPrebuilt(id, s))) {
                        stale++;
                    }
                }
            }
            long keys = runner.countKeys("img:*");
            long live = (long) items * sizes.length;
            ReplaceResult r = new ReplaceResult(strategy, items, requests, stale, requests == 0 ? 0 : (double) stale / requests,
                    invalidateNanos / 1e6, deleted, keys, Math.max(0, keys - live), ttlSec);
            events.success(strategy, String.format("완료: 옛 이미지 %d/%d건(%.0f%%), 쓸모없는 옛 키 %d개", stale, requests, r.stalePct() * 100, r.orphanKeys()));
            return r;
        } catch (Exception e) {
            events.error(strategy, "실패: " + e.getMessage());
            throw e;
        } finally {
            guard.release();
        }
    }
}
