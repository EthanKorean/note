package com.ethan.note.image;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** 이미지 만들기·캐시 실험의 진행 단계를 STOMP(/topic/image)로 내보낸다. 형식은 juso 의 LoadEvents 와 같다. */
@Component
public class ImageEvents {

    public static final String TOPIC = "/topic/image";
    private static final Logger log = LoggerFactory.getLogger(ImageEvents.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** @param level info | success | error */
    public record Event(String level, String method, String message, String time) {
    }

    private final SimpMessagingTemplate template;

    public ImageEvents(SimpMessagingTemplate template) {
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
        try {
            template.convertAndSend(TOPIC, new Event(level, method, message, LocalDateTime.now().format(TIME)));
        } catch (Exception ex) {
            log.debug("웹소켓 전송 실패: {}", ex.toString());
        }
    }
}
