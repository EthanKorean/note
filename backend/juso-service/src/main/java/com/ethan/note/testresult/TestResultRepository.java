package com.ethan.note.testresult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 어떤 종류의 테스트든 결과를 남기는 범용 저장소(test_result 테이블).
 * 결과 형식이 제각각이라 result는 JSON 문자열로 저장하고, 거르는 데 쓰는 값만 컬럼으로 둔다.
 * (다른 서비스에서도 쓰게 되면 이 패키지를 common 모듈로 옮기면 된다.)
 */
@Repository
public class TestResultRepository {

    public static final String SUCCESS = "success";
    public static final String FAIL = "fail";

    private static final Logger log = LoggerFactory.getLogger(TestResultRepository.class);

    /**
     * @param testType 테스트 종류 (예: load, search, mqtt-throughput)
     * @param name     무엇을 테스트했나 (예: 적재 방식 이름)
     * @param variant  조건/구분값 (예: 엔진). 없으면 null
     * @param status   success | fail
     */
    public record Row(long id, String testType, String name, String variant, String status,
                      String executedAt, long elapsedMs, JsonNode result) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TestResultRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 저장에 실패해도 테스트 자체를 실패로 만들지 않는다. */
    public void saveQuietly(String testType, String name, String variant, String status,
                            long elapsedMs, Object result) {
        try {
            jdbc.update("INSERT INTO test_result (test_type, name, variant, status, elapsed_ms, result) "
                            + "VALUES (?,?,?,?,?,?)",
                    testType, name, variant, status, elapsedMs, mapper.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("테스트 결과 저장 실패 ({} / {})", testType, name, e);
        }
    }

    /** 최근 순. 조건이 null이면 거르지 않는다. */
    public List<Row> list(String testType, String name, String status, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, test_type, name, variant, status, "
                        + "DATE_FORMAT(executed_at, '%Y-%m-%d %H:%i:%s') AS executed_at, elapsed_ms, result "
                        + "FROM test_result WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (testType != null) {
            sql.append(" AND test_type = ?");
            args.add(testType);
        }
        if (name != null) {
            sql.append(" AND name = ?");
            args.add(name);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);

        return jdbc.query(sql.toString(), (rs, i) -> {
            try {
                return new Row(rs.getLong("id"), rs.getString("test_type"), rs.getString("name"),
                        rs.getString("variant"), rs.getString("status"), rs.getString("executed_at"),
                        rs.getLong("elapsed_ms"), mapper.readTree(rs.getString("result")));
            } catch (Exception e) {
                throw new IllegalStateException("저장된 결과를 읽을 수 없습니다: id=" + rs.getLong("id"), e);
            }
        }, args.toArray());
    }

    public int clear(String testType) {
        return testType == null
                ? jdbc.update("DELETE FROM test_result")
                : jdbc.update("DELETE FROM test_result WHERE test_type = ?", testType);
    }
}
