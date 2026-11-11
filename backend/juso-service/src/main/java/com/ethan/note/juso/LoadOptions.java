package com.ethan.note.juso;

/**
 * 적재 옵션.
 *
 * @param engine      innodb (Aria는 비교에서 제외)
 * @param batchSize   배치/청크 크기(행). 멀티 로우는 2849 이하(바인드 변수 65,535개 제한)
 * @param threads     parallel 방식의 워커 수
 * @param deferIndex  true면 보조 인덱스를 지우고 적재한 뒤 다시 만든다
 * @param relaxChecks true면 세션에서 unique_checks/foreign_key_checks를 끈다
 * @param truncate    적재 전에 테이블을 비운다
 */
public record LoadOptions(
        String engine,
        int batchSize,
        int threads,
        boolean deferIndex,
        boolean relaxChecks,
        boolean truncate) {

    public LoadOptions {
        engine = engine == null ? null : engine.toLowerCase(java.util.Locale.ROOT);
        if (batchSize < 1 || batchSize > 50_000) {
            throw new IllegalArgumentException("batchSize는 1~50000");
        }
        if (threads < 1 || threads > 8) {
            throw new IllegalArgumentException("threads는 1~8");
        }
    }
}
