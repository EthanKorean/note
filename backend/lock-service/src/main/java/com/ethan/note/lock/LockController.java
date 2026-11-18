package com.ethan.note.lock;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lock")
public class LockController {

    private static final Logger log = LoggerFactory.getLogger(LockController.class);

    public record Status(boolean running, String current, String error, List<String> experiments) {
    }

    private final LockLab lab;
    private final WorkGuard guard;
    private final ResultStore store;
    private final LockEvents events;

    public LockController(LockLab lab, WorkGuard guard, ResultStore store, LockEvents events) {
        this.lab = lab;
        this.guard = guard;
        this.store = store;
        this.events = events;
    }

    @GetMapping("/status")
    public Status status() {
        String current = guard.current(); // 한 번만 읽는다(두 번 읽는 사이에 끝나면 running 과 current 가 어긋난다)
        return new Status(current != null, current, lab.check(), Arrays.stream(Experiment.values()).map(Experiment::id).toList());
    }

    /** 실험 하나를 실행한다. 끝날 때까지 응답이 오지 않는다(몇 초). */
    @PostMapping("/run")
    public LockLab.Result run(@RequestBody LockLab.Params params) {
        LockLab.validate(params); // 잘못된 입력은 잠금을 잡기 전에 거절한다
        guard.acquire(Experiment.of(params.id()).title());
        try {
            LockLab.Result r = lab.run(params);
            boolean saved = store.save(r.id(), "success", r.elapsedMs(), r);
            return saved ? r : r.withSaved(false);
        } catch (RuntimeException e) {
            // 자세한 로그는 아래 예외 처리기가 한 번만 남긴다.
            events.error("lock", "실험 실패: " + (e instanceof LockException ? e.getMessage() : "서버 오류(lock-service 로그 확인)"));
            throw e;
        } finally {
            guard.release();
        }
    }

    @ExceptionHandler(BusyException.class)
    public ResponseEntity<Map<String, String>> busy(BusyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** 사용자가 보낸 값이 잘못된 경우만 400. 서버 안쪽의 IllegalArgumentException 은 아래 일반 처리(500)로 가서 내부 메시지가 나가지 않는다. */
    @ExceptionHandler({BadRequestException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        String msg = e instanceof HttpMessageNotReadableException ? "요청 본문을 읽을 수 없습니다." : String.valueOf(e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** 실험을 끝까지 하지 못한 경우. 메시지는 화면에 보여 줘도 되는 문장이다. */
    @ExceptionHandler(LockException.class)
    public ResponseEntity<Map<String, String>> lockFailed(LockException e) {
        log.error("실험 실패", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", String.valueOf(e.getMessage())));
        }
        log.error("요청 처리 실패", e);
        // 드라이버 예외 메시지에는 DB 주소 같은 내부 정보가 들어 있을 수 있어 화면에는 일반 문구만 보낸다(자세한 내용은 위 로그).
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "서버 오류가 났습니다. lock-service 로그를 확인하세요."));
    }
}
