package com.ethan.note.juso.loader;

import com.ethan.note.juso.JusoColumns;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 파일을 스트리밍으로 읽어 N행씩 청크로 넘긴다(전체를 메모리에 올리지 않음). */
public final class Rows {

    private static final Logger log = LoggerFactory.getLogger(Rows.class);
    private static final long LOG_EVERY = 500_000;

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(List<String[]> chunk) throws Exception;
    }

    private Rows() {
    }

    public static BufferedReader reader(Path file) throws IOException {
        // 원본은 CP949(MS949)
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), Charset.forName("MS949").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)), 1 << 20);
    }

    public static long forEachChunk(Path file, int size, ChunkConsumer consumer) throws Exception {
        long total = 0;
        long nextLog = LOG_EVERY;
        List<String[]> chunk = new ArrayList<>(size);
        try (BufferedReader br = reader(file)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                chunk.add(JusoColumns.parse(line));
                if (chunk.size() == size) {
                    consumer.accept(chunk);
                    total += chunk.size();
                    chunk = new ArrayList<>(size);   // 소비자가 참조를 들고 있을 수 있으므로 새 리스트 사용
                    if (total >= nextLog) {
                        log.info("  ... {}건 처리", String.format("%,d", total));
                        nextLog += LOG_EVERY;
                    }
                }
            }
            if (!chunk.isEmpty()) {
                consumer.accept(chunk);
                total += chunk.size();
            }
        }
        return total;
    }
}
