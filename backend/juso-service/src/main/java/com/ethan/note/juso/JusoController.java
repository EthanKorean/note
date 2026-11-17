package com.ethan.note.juso;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/juso")
public class JusoController {

    private static final Logger log = LoggerFactory.getLogger(JusoController.class);
    private final JusoLoadService loadService;
    private final JusoQueryService queryService;

    public JusoController(JusoLoadService loadService, JusoQueryService queryService) {
        this.loadService = loadService;
        this.queryService = queryService;
    }

    /**
     * 서버에 설정된 데이터 파일(juso.data-file)을 지정한 방식으로 적재한다. 업로드는 없다.
     * 예) curl -X POST 'http://localhost:9203/api/juso/load/loaddata-local?deferIndex=true'
     */
    @PostMapping("/load/{method}")
    public LoadResult load(@PathVariable String method,
                           @RequestParam(defaultValue = "innodb") String engine,
                           @RequestParam(defaultValue = "1000") int batchSize,
                           @RequestParam(defaultValue = "4") int threads,
                           @RequestParam(defaultValue = "false") boolean deferIndex,
                           @RequestParam(defaultValue = "true") boolean relaxChecks,
                           @RequestParam(defaultValue = "true") boolean truncate) throws Exception {
        LoadOptions options = new LoadOptions(engine, batchSize, threads, deferIndex, relaxChecks, truncate);
        return loadService.load(method, options);
    }

    /** 적재가 진행 중인지(화면이 새로 열렸을 때 버튼 상태를 맞추기 위해). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("running", loadService.isRunning());
    }

    @GetMapping("/methods")
    public List<String> methods() {
        return loadService.methods();
    }

    @PostMapping("/truncate")
    public Map<String, String> truncate(@RequestParam(defaultValue = "all") String engine) {
        loadService.truncate(engine);
        return Map.of("truncated", engine);
    }

    @GetMapping("/tables")
    public List<JusoQueryService.TableInfo> tables() {
        return queryService.tables();
    }

    @GetMapping("/search")
    public JusoQueryService.SearchResult search(@RequestParam(defaultValue = "innodb") String engine,
                                                @RequestParam(required = false) String roadNm,
                                                @RequestParam(required = false) String zip,
                                                @RequestParam(defaultValue = "20") int limit) {
        return queryService.search(engine, roadNm, zip, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(LoadBusyException.class)
    ResponseEntity<Map<String, String>> conflict(LoadBusyException e) {
        return error(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, String>> badParam(Exception e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    /** 데이터 오류는 입력 검증 실패(400)가 아니라 적재 실패로 본다. */
    @ExceptionHandler(NumberFormatException.class)
    ResponseEntity<Map<String, String>> badData(NumberFormatException e) {
        return failed(e);
    }

    @ExceptionHandler({Exception.class})
    ResponseEntity<Map<String, String>> failed(Exception e) {
        log.error("요청 처리 실패", e);
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = e instanceof IOException ? "I/O 오류: " + root.getMessage()
                : e.getClass().getSimpleName() + ": " + root.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", PathMask.scrub(String.valueOf(msg))));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, Exception e) {
        return ResponseEntity.status(status).body(Map.of("error", PathMask.scrub(String.valueOf(e.getMessage()))));
    }
}
