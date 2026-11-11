package com.ethan.note.juso;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** 적재 단계(시작, 인덱스 처리, 적재 완료, 검증, 결과)를 STOMP(/topic/juso)로 내보낸다. */
@Component
public class LoadEvents {

    public static final String TOPIC = "/topic/juso";
    private static final Logger log = LoggerFactory.getLogger(LoadEvents.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** @param level info | success | error */
    public record Event(String level, String method, String message, String time) {
    }

    private final SimpMessagingTemplate template;
    public LoadEvents(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void info(String method, String message) {
        send(new Event("info", method, message, now()));
    }

    public void success(String method, String message) {
        send(new Event("success", method, message, now()));
    }

    public void error(String method, String message) {
        send(new Event("error", method, message, now()));
    }

    private void send(Event e) {
        try {
            template.convertAndSend(TOPIC, e);
        } catch (Exception ex) {
            log.debug("웹소켓 전송 실패: {}", ex.toString());
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }
}
