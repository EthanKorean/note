package com.ethan.note.toast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * claude-toast. Claude Code 훅이 POST /api/toast 로 보낸 알림을 STOMP(/topic/toast)로 브라우저에 뿌려
 * 화면 구석에 토스트로 띄운다. 상태는 갖지 않는다(받은 즉시 전달하고 잊는다).
 */
@SpringBootApplication
public class ToastServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ToastServiceApplication.class, args);
    }
}
