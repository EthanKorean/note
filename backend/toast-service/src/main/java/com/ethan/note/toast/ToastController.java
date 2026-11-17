package com.ethan.note.toast;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/toast")
public class ToastController {
    private static final Set<String> KINDS = Set.of("done", "attention", "error", "info");
    private static final Map<String, String> DEFAULT_TITLE =
            Map.of("done", "작업이 끝났어요", "attention", "확인이 필요해요", "error", "문제가 생겼어요", "info", "알림");

    private final SimpMessagingTemplate broker;
    private final Subscribers subscribers;

    public ToastController(SimpMessagingTemplate broker, Subscribers subscribers) {
        this.broker = broker;
        this.subscribers = subscribers;
    }

    /**
     * 알림을 받아 브라우저로 전달한다. application/json 만 받는다(다른 사이트의 폼 전송이 몰래 알림을 만들지 못하게).
     * 예) curl -X POST localhost:9208/api/toast -H 'Content-Type: application/json' -d '{"kind":"done","message":"빌드 끝"}'
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> send(@RequestBody ToastRequest r) {
        String kind = r.kind() == null || r.kind().isBlank() ? "info" : r.kind();
        if (!KINDS.contains(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "kind 는 " + KINDS + " 중 하나여야 합니다: " + clip(kind, 20)));
        }
        String title = r.title() == null || r.title().isBlank() ? DEFAULT_TITLE.get(kind) : clip(r.title(), 80);
        Toast toast = new Toast(UUID.randomUUID().toString(), kind, title,
                clip(r.message(), 300), clip(r.project(), 60), OffsetDateTime.now().toString());
        broker.convertAndSend(WebSocketConfig.TOPIC, toast);
        return ResponseEntity.ok(Map.of("id", toast.id(), "subscribers", subscribers.count()));
    }

    /** 토스트를 받는 브라우저 연결 수(상태 확인용). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("subscribers", subscribers.count());
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        if (t.codePointCount(0, t.length()) <= max) return t;
        return t.substring(0, t.offsetByCodePoints(0, max - 1)) + "…"; // 이모지 같은 두 글자 문자가 잘려 깨지지 않게 글자 단위로 자른다
    }
}
