package com.ethan.note.image;

/**
 * 캐시 실험 조건. 화면에서 모두 바꿀 수 있다.
 *
 * @param mode             nocache | prebuilt | redis
 * @param requests         총 요청 수
 * @param threads          동시에 요청하는 스레드 수
 * @param products         전체 상품 수(1..products 중에서 고른다. 시딩된 수 이하)
 * @param hotSetSize       지금 인기 있는 상품 수
 * @param hotRatio         요청 중 인기 상품에 몰리는 비율(0~1). 나머지는 전체에서 고르게
 * @param rotateEvery      이 요청 수마다 인기 상품 집합이 통째로 바뀐다(0이면 안 바뀜)
 * @param originLatencyMs  원본 저장소(원격 스토리지)를 흉내 내는 읽기 지연(ms). 로컬 디스크는 너무 빨라서 필요
 * @param ttlSec           Redis 에 둔 이미지의 유효 시간(초)
 * @param hotThreshold     윈도 안에 이 횟수 이상 캐시를 못 찾은 상품만 Redis 에 올린다
 * @param windowSec        "자주 본다"를 세는 시간 창(초)
 * @param stampedeGuard    같은 이미지를 동시에 읽을 때 한 번만 읽고 나머지는 기다리게 한다
 * @param maxMemoryMb      Redis maxmemory (MB)
 * @param evictionPolicy   Redis 가득 찼을 때 제거 정책 (allkeys-lfu | allkeys-lru | volatile-ttl | noeviction)
 * @param resetCache       실험 전에 이 실험의 Redis 키를 지운다
 */
public record BenchParams(
        String mode,
        int requests,
        int threads,
        int products,
        int hotSetSize,
        double hotRatio,
        int rotateEvery,
        int originLatencyMs,
        int ttlSec,
        int hotThreshold,
        int windowSec,
        boolean stampedeGuard,
        int maxMemoryMb,
        String evictionPolicy,
        boolean resetCache) {

    public BenchParams {
        CacheMode.of(mode);
        range("requests", requests, 100, 2_000_000);
        range("threads", threads, 1, 64);
        range("products", products, 1, 100_000);
        range("hotSetSize", hotSetSize, 1, products);
        if (hotRatio < 0 || hotRatio > 1) {
            throw new IllegalArgumentException("hotRatio는 0~1");
        }
        range("rotateEvery", rotateEvery, 0, 2_000_000);
        range("originLatencyMs", originLatencyMs, 0, 500);
        range("ttlSec", ttlSec, 1, 86_400);
        range("hotThreshold", hotThreshold, 1, 1000);
        range("windowSec", windowSec, 1, 3600);
        range("maxMemoryMb", maxMemoryMb, 1, 4096);
        // 원본 지연이 큰 채로 요청이 많으면 실험이 끝나지 않아 다른 작업을 막는다. 이론상 최소 소요 시간으로 제한한다.
        if ((long) requests * originLatencyMs / Math.max(1, threads) > 600_000L) {
            throw new IllegalArgumentException("원본 지연 × 요청 수 ÷ 스레드가 10분을 넘습니다. 값을 줄이세요.");
        }
        if (evictionPolicy == null || !java.util.Set.of("allkeys-lfu", "allkeys-lru", "volatile-ttl", "noeviction").contains(evictionPolicy)) {
            throw new IllegalArgumentException("지원하지 않는 제거 정책: " + evictionPolicy);
        }
    }

    /** 이미지 한 장을 내려 주는 주소(/api/image/{id})가 쓰는 조건. 실험 값이 아니라 서비스용 기본값이다. */
    public static BenchParams forServing(String mode) {
        return forServing(mode, 60);
    }

    public static BenchParams forServing(String mode, int ttlSec) {
        return new BenchParams(mode, 100, 1, 1, 1, 0, 0, 0, ttlSec, 1, 60, false, 128, "allkeys-lfu", false);
    }

    public CacheMode cacheMode() {
        return CacheMode.of(mode);
    }

    private static void range(String name, int v, int min, int max) {
        if (v < min || v > max) {
            throw new IllegalArgumentException(name + "는 " + min + "~" + max);
        }
    }
}
