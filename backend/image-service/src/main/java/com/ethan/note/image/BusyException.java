package com.ethan.note.image;

/** 다른 작업(실험, 이미지 생성, 캐시 비우기)이 실행 중일 때. 409 로 응답한다. */
public class BusyException extends RuntimeException {

    public BusyException(String message) {
        super(message);
    }
}
