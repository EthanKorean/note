package com.ethan.note.juso;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// testresult(범용 테스트 결과) 패키지도 함께 스캔한다. 나중에 common 모듈로 옮겨도 같은 설정으로 동작.
@SpringBootApplication(scanBasePackages = "com.ethan.note")
public class JusoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(JusoServiceApplication.class, args);
    }
}
