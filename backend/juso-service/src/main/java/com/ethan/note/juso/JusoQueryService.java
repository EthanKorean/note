package com.ethan.note.juso;

import com.ethan.note.testresult.TestResultRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 테이블 상태와 조회 성능 확인용 쿼리. */
@Service
public class JusoQueryService {

    public record TableInfo(String engineKey, String table, String engine, long count, long countMs,
                            double dataMb, double indexMb) {
    }

    public record SearchResult(String table, long elapsedMs, int size, List<Map<String, Object>> rows) {
    }

    private final JdbcTemplate jdbc;
    private final TestResultRepository results;

    public JusoQueryService(JdbcTemplate jdbc, TestResultRepository results) {
        this.jdbc = jdbc;
        this.results = results;
    }

    public List<TableInfo> tables() {
        List<TableInfo> result = new ArrayList<>();
        for (var e : new java.util.TreeMap<>(JusoTables.BY_ENGINE).entrySet()) {
            String table = e.getValue();
            // 크기 통계(information_schema)는 갱신이 늦어 InnoDB가 0으로 보일 수 있어 먼저 갱신한다.
            long t = System.nanoTime();
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            long countMs = (System.nanoTime() - t) / 1_000_000;
            Map<String, Object> meta = jdbc.queryForMap(
                    "SELECT ENGINE, DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", table);
            result.add(new TableInfo(e.getKey(), table, String.valueOf(meta.get("ENGINE")),
                    count == null ? 0 : count, countMs,
                    ((Number) meta.get("DATA_LENGTH")).doubleValue() / 1_048_576,
                    ((Number) meta.get("INDEX_LENGTH")).doubleValue() / 1_048_576));
        }
        return result;
    }

    public SearchResult search(String engine, String roadNm, String zip, int limit) {
        String table = JusoTables.resolve(engine);
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (roadNm != null && !roadNm.isBlank()) {
            where.add("road_nm = ?");
            args.add(roadNm);
        }
        if (zip != null && !zip.isBlank()) {
            where.add("zip_code = ?");
            args.add(zip);
        }
        if (where.isEmpty()) {
            throw new IllegalArgumentException("roadNm 또는 zip 중 하나는 필요합니다.");
        }
        args.add(Math.max(1, Math.min(limit, 200)));
        long t = System.nanoTime();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE " + String.join(" AND ", where) + " LIMIT ?", args.toArray());
        long elapsedMs = (System.nanoTime() - t) / 1_000_000;
        results.saveQuietly("search", "search", engine, TestResultRepository.SUCCESS, elapsedMs, Map.of(
                "table", table, "roadNm", roadNm == null ? "" : roadNm, "zip", zip == null ? "" : zip,
                "size", rows.size(), "elapsedMs", elapsedMs));
        return new SearchResult(table, elapsedMs, rows.size(), rows);
    }
}
