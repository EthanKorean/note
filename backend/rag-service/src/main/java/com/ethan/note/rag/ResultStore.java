package com.ethan.note.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 평가 결과를 juso-service 와 같은 test_result 테이블에 남긴다(test_type = rag-eval). */
@Repository
public class ResultStore {

    public static final String TYPE = "rag-eval";
    private static final Logger log = LoggerFactory.getLogger(ResultStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ResultStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** @return 저장에 성공했으면 true. 실패해도 평가 자체는 끝난 것이라 예외를 던지지 않고, 호출한 쪽이 화면에 알린다. */
    public boolean save(String name, String status, long elapsedMs, Object result) {
        try {
            jdbc.update("INSERT INTO test_result (test_type, name, variant, status, elapsed_ms, result) VALUES (?,?,?,?,?,?)",
                    TYPE, name, null, status, elapsedMs, mapper.writeValueAsString(result));
            return true;
        } catch (Exception e) {
            log.warn("결과 저장 실패", e);
            return false;
        }
    }
}
