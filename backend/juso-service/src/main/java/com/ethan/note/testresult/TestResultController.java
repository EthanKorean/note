package com.ethan.note.testresult;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 저장된 테스트 결과 조회/삭제. 테스트 종류(type)에 관계없이 같은 API를 쓴다. */
@RestController
@RequestMapping("/api/test-results")
public class TestResultController {

    private final TestResultRepository repository;

    public TestResultController(TestResultRepository repository) {
        this.repository = repository;
    }

    /** 예) /api/test-results?type=load&status=success&limit=50 */
    @GetMapping
    public List<TestResultRepository.Row> list(@RequestParam(required = false) String type,
                                               @RequestParam(required = false) String name,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "100") int limit) {
        return repository.list(type, name, status, Math.max(1, Math.min(limit, 1000)));
    }

    @DeleteMapping
    public Map<String, Integer> clear(@RequestParam String type) {
        return Map.of("deleted", repository.clear(type));
    }
}
