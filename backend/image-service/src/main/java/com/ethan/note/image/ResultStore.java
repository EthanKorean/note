package com.ethan.note.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 실험 결과를 juso-service 와 같은 test_result 테이블에 남긴다(test_type = image-cache). */
@Repository
public class ResultStore {

    private static final Logger log = LoggerFactory.getLogger(ResultStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ResultStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(String mode, String status, long elapsedMs, Object result) {
        save("image-cache", mode, status, elapsedMs, result);
    }

    public void save(String type, String mode, String status, long elapsedMs, Object result) {
        try {
            jdbc.update("INSERT INTO test_result (test_type, name, variant, status, elapsed_ms, result) VALUES (?,?,?,?,?,?)",
                    type, mode, null, status, elapsedMs, mapper.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("결과 저장 실패", e);
        }
    }
}
