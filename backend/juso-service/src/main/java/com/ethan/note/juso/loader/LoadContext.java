package com.ethan.note.juso.loader;

import java.nio.file.Path;

/**
 * 한 번의 적재 작업에 필요한 입력.
 *
 * @param file       적재할 원본 파일(CP949, '|' 구분)
 * @param serverPath DB 컨테이너 안에서 보이는 같은 파일의 경로(loaddata-server 용)
 */
public record LoadContext(
        Path file,
        String serverPath,
        String table,
        int batchSize,
        int threads,
        boolean relaxChecks) {
}
