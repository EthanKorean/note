package com.ethan.note.image;

/**
 * 이미지 교체 실험 결과.
 *
 * @param strategy      none(아무것도 안 함) | purge(캐시 삭제) | version(버전 키)
 * @param items         교체한 상품 수
 * @param requests      교체 후 다시 요청한 횟수(상품 x 크기 3종)
 * @param stale         교체 전 이미지를 받은 횟수
 * @param stalePct      stale / requests
 * @param invalidateMs  무효화 작업에만 쓴 시간(ms, 파일 교체 시간 제외)
 * @param keysDeleted   무효화로 지운 Redis 키 수
 * @param keysInRedis   확인이 끝난 뒤 Redis 에 남아 있는 이미지 키 수
 * @param orphanKeys    그중 아무도 읽지 않는 옛 키 수(버전 방식은 TTL 로 사라질 때까지 메모리를 차지한다)
 * @param ttlSec        캐시 유효 시간
 */
public record ReplaceResult(
        String strategy,
        int items,
        int requests,
        int stale,
        double stalePct,
        double invalidateMs,
        long keysDeleted,
        long keysInRedis,
        long orphanKeys,
        int ttlSec) {
}
