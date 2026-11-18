package com.ethan.note.lock;

/** 실험을 끝까지 하지 못한 경우(초기화 실패, 제한 시간 초과 등). 메시지는 화면에 그대로 보여 줘도 되는 문장이다(DB 주소 같은 내부 정보를 넣지 않는다). */
public class LockException extends RuntimeException {

    public LockException(String message, Throwable cause) {
        super(message, cause);
    }

    public LockException(String message) {
        super(message);
    }
}
