package com.ethan.note.image;

/**
 * 실험 결과. 시간은 ms, 적중률은 0~1.
 *
 * @param timelineHit     요청 순서를 20구간으로 나눈 구간별 적중률 (인기 집합이 바뀔 때의 변화를 본다)
 * @param timelineLatency 구간별 평균 응답 시간(ms)
 * @param originReads     원본(디스크/원격 스토리지)을 실제로 읽은 횟수 — 캐시가 막아 준 만큼 줄어든다
 */
public record BenchResult(
        BenchParams params,
        long elapsedMs,
        double throughput,
        double avgMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double hitRatio,
        long originReads,
        long bytesServed,
        long evictedKeys,
        double redisUsedMb,
        double[] timelineHit,
        double[] timelineLatency) {
}
