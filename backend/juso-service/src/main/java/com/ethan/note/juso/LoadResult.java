package com.ethan.note.juso;

/**
 * 적재 결과. 시간은 모두 밀리초.
 *
 * @param fileLines           적재한 행 수(파일의 총 건수)
 * @param verifiedCount       적재 후 SELECT COUNT(*) 결과 (fileLines와 같아야 정상)
 * @param prepareMs           적재 전 파일 행 수를 세는 데 걸린 시간
 * @param loadMs              DB 적재만 걸린 시간 (인덱스 재생성 제외)
 * @param indexMs             deferIndex 사용 시 인덱스 삭제+재생성 시간
 * @param totalMs             서버가 요청을 처리한 전체 시간
 * @param rowsPerSec          fileLines / loadMs
 * @param rowsPerSecWithIndex fileLines / (loadMs + indexMs) — deferIndex 공정 비교용
 * @param mbPerSec            fileBytes / loadMs
 */
public record LoadResult(
        String method,
        String engine,
        String table,
        long fileLines,
        long fileBytes,
        long verifiedCount,
        long prepareMs,
        long loadMs,
        long indexMs,
        long totalMs,
        double rowsPerSec,
        double rowsPerSecWithIndex,
        double mbPerSec,
        LoadOptions options) {
}
