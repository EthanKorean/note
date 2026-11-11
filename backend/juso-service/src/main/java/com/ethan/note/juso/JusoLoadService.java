package com.ethan.note.juso;

import com.ethan.note.juso.loader.JusoLoader;
import com.ethan.note.juso.loader.LoadContext;
import com.ethan.note.testresult.TestResultRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 행 수 세기 → 적재 → 인덱스 처리 → 건수 검증, 각 단계 시간을 측정한다. */
@Service
public class JusoLoadService {

    private static final Logger log = LoggerFactory.getLogger(JusoLoadService.class);
    private static final int BUF = 1 << 20;

    private final Map<String, JusoLoader> loaders;
    private final JdbcTemplate jdbc;
    private final TestResultRepository results;
    private final LoadEvents events;
    private final Path dataFile;
    private final String serverDataFile;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public JusoLoadService(List<JusoLoader> loaderList, JdbcTemplate jdbc, TestResultRepository results,
                           LoadEvents events,
                           @Value("${juso.data-file}") String dataFile,
                           @Value("${juso.server-data-file}") String serverDataFile) {
        this.loaders = loaderList.stream().collect(Collectors.toMap(JusoLoader::name, Function.identity()));
        this.jdbc = jdbc;
        this.results = results;
        this.events = events;
        this.dataFile = Path.of(dataFile).toAbsolutePath().normalize();
        this.serverDataFile = serverDataFile;
    }

    public List<String> methods() {
        return loaders.keySet().stream().sorted().toList();
    }

    public LoadResult load(String method, LoadOptions o) throws Exception {
        JusoLoader loader = loaders.get(method);
        if (loader == null) {
            throw new IllegalArgumentException("지원하지 않는 방식: " + method + " (사용 가능: " + methods() + ")");
        }
        String table = JusoTables.resolve(o.engine());
        if ((method.startsWith("jpa") || method.startsWith("mybatis")) && o.relaxChecks()) {
            // JPA/MyBatis는 자체 커넥션을 써서 검사를 끌 수 없으므로 기록되는 옵션도 false로 맞춘다.
            o = new LoadOptions(o.engine(), o.batchSize(), o.threads(), o.deferIndex(), false, o.truncate());
        }
        if (!Files.isReadable(dataFile)) {
            throw new IllegalArgumentException("데이터 파일이 없습니다: " + dataFile
                    + " (generate-data/generate_juso.py 로 만들거나 juso.data-file 을 지정하세요)");
        }

        // 벤치마크 결과가 섞이지 않도록 한 번에 하나만 실행한다.
        if (!running.compareAndSet(false, true)) {
            throw new LoadBusyException("다른 적재 작업이 실행 중입니다.");
        }
        boolean indexesDropped = false;
        long t0 = System.nanoTime();
        try {
            events.info(method, "시작: " + method + " / " + o.engine()
                    + (o.deferIndex() ? " / 인덱스 나중에 생성" : ""));

            // 1) 행 수 세기
            events.info(method, "파일 행 수 세는 중");
            long lines = countLines(dataFile);
            long fileBytes = Files.size(dataFile);
            long prepareMs = msSince(t0);
            log.info("[{}/{}] 적재 시작: {}건 ({} MB), 행 수 세기 {} ms",
                    method, o.engine(), String.format("%,d", lines), fileBytes / 1_000_000, prepareMs);
            events.info(method, String.format("총 %,d건 (%d MB)", lines, fileBytes / 1_000_000));

            // 2) 준비: 검증(데이터를 건드리기 전), 비우기, (선택) 인덱스 제거
            LoadContext ctx = new LoadContext(dataFile, serverDataFile, table, o.batchSize(), o.threads(), o.relaxChecks());
            loader.validate(ctx);
            if (o.truncate()) {
                events.info(method, "테이블 비우는 중");
                jdbc.execute("TRUNCATE TABLE " + table);
            }
            long indexMs = 0;
            if (o.deferIndex()) {
                events.info(method, "보조 인덱스 제거");
                indexesDropped = true;
                long t = System.nanoTime();
                jdbc.execute(JusoTables.dropIndexesSql(table));
                indexMs += msSince(t);
            }

            // 3) 적재 (이 구간이 loadMs)
            events.info(method, "적재 중");
            long tLoad = System.nanoTime();
            loader.load(ctx);
            long loadMs = msSince(tLoad);
            events.info(method, "적재 완료 " + loadMs + "ms");

            // 4) (선택) 인덱스 재생성
            if (o.deferIndex()) {
                events.info(method, "보조 인덱스 생성 중");
                long t = System.nanoTime();
                jdbc.execute(JusoTables.addIndexesSql(table));
                indexesDropped = false;
                indexMs += msSince(t);
            }

            // 5) 검증
            events.info(method, "건수 검증 중");
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            long verified = count == null ? -1 : count;
            if (o.truncate() && verified != lines) {
                // LOAD DATA LOCAL의 중복 무시, JPA merge 등으로 건수가 어긋난 실행은 성공으로 기록하지 않는다.
                throw new IllegalStateException("적재 건수 불일치: 파일 " + lines + "건, DB " + verified + "건");
            }

            long totalMs = msSince(t0);
            try {
                jdbc.queryForList("ANALYZE TABLE " + table);   // 화면의 크기 통계용 (측정 시간에는 포함하지 않음)
            } catch (Exception ae) {
                log.warn("ANALYZE 실패(무시): {}", table, ae);
            }
            double rowsPerSec = perSec(lines, loadMs);
            double withIndex = perSec(lines, loadMs + indexMs);
            double mbPerSec = perSec(fileBytes, loadMs) / 1_000_000.0;
            log.info("[{}/{}] 완료: {}건, 적재 {} ms, 인덱스 {} ms, {}건/초, 검증 COUNT={}",
                    method, o.engine(), String.format("%,d", lines), loadMs, indexMs,
                    String.format("%,.0f", rowsPerSec), String.format("%,d", verified));

            LoadResult result = new LoadResult(method, o.engine(), table, lines, fileBytes, verified,
                    prepareMs, loadMs, indexMs, totalMs, rowsPerSec, withIndex, mbPerSec, o);
            results.saveQuietly("load", method, o.engine(), TestResultRepository.SUCCESS, totalMs, result);
            events.success(method, String.format("완료: %,d건 · 총 %.1f초 · %,.0f건/초",
                    verified, totalMs / 1000.0, rowsPerSec));
            return result;
        } catch (Exception e) {
            // 사용자 입력 오류(400)는 기록하지 않고, 실행 중 실패만 남긴다.
            if (!(e instanceof IllegalArgumentException) || e instanceof NumberFormatException) {
                log.error("[{}/{}] 적재 실패", method, o.engine(), e);
                results.saveQuietly("load", method, o.engine(), TestResultRepository.FAIL, msSince(t0),
                        Map.of("error", rootMessage(e), "options", o));
                events.error(method, "실패: " + rootMessage(e));
            }
            throw e;
        } finally {
            if (indexesDropped) {
                // 실패(Error 포함)해도 인덱스는 되돌려 다음 실행이 깨지지 않게 한다.
                try {
                    jdbc.execute(JusoTables.addIndexesSql(table));
                } catch (Exception re) {
                    log.warn("인덱스 복구 실패: {}", table, re);
                }
            }
            running.set(false);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 적재 도중에 비우면 결과가 깨지므로 막는다. */
    public void truncate(String engine) {
        if (!running.compareAndSet(false, true)) {
            throw new LoadBusyException("적재가 진행 중이라 테이블을 비울 수 없습니다.");
        }
        try {
            truncateTables(engine);
        } finally {
            running.set(false);
        }
    }

    private void truncateTables(String engine) {
        if ("all".equalsIgnoreCase(engine)) {
            JusoTables.BY_ENGINE.values().forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        } else {
            jdbc.execute("TRUNCATE TABLE " + JusoTables.resolve(engine));
        }
    }

    /** 개행 수로 행 수를 센다. 마지막 줄에 개행이 없으면 한 줄을 더한다. */
    static long countLines(Path file) throws IOException {
        long lines = 0;
        int last = '\n';
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        lines++;
                    }
                }
                last = buf[n - 1];
            }
        }
        return last == '\n' ? lines : lines + 1;
    }

    private static long msSince(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private static double perSec(double amount, long ms) {
        return ms <= 0 ? 0 : amount * 1000.0 / ms;
    }
}
