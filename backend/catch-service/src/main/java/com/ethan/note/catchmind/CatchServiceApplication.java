package com.ethan.note.catchmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 캐치마인드(그림 맞추기) 서비스. 방은 하나이고 상태는 서버 메모리에만 둔다. */
@SpringBootApplication
public class CatchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatchServiceApplication.class, args);
    }
}
