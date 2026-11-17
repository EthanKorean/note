package com.ethan.note.rag;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** 모델 준비·검색 평가의 진행 단계를 STOMP(/topic/rag)로 내보낸다. 형식은 juso 의 LoadEvents 와 같다. */
@Component
public class RagEvents {

    public static final String TOPIC = "/topic/rag";
    private static final Logger log = LoggerFactory.getLogger(RagEvents.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** @param level info | success | error */
    public record Event(String level, String method, String message, String time) {
    }

    private final SimpMessagingTemplate template;

    public RagEvents(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void info(String method, String message) {
        send("info", method, message);
    }

    public void success(String method, String message) {
        send("success", method, message);
    }

    public void error(String method, String message) {
        send("error", method, message);
    }

    private void send(String level, String method, String message) {
        log.info("[{}] {}", method, message);
        try {
            template.convertAndSend(TOPIC, new Event(level, method, message, LocalDateTime.now().format(TIME)));
        } catch (Exception ex) {
            log.debug("웹소켓 전송 실패: {}", ex.toString());
        }
    }
}
