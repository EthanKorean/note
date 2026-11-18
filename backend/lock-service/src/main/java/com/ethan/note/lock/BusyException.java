package com.ethan.note.lock;

/** 다른 실험이 실행 중일 때. 409 로 응답한다. */
public class BusyException extends RuntimeException {

    public BusyException(String message) {
        super(message);
    }
}
