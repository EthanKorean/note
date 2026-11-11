package com.ethan.note.juso;

/** 다른 적재가 진행 중이라 요청을 받을 수 없을 때(HTTP 409). */
public class LoadBusyException extends RuntimeException {
    public LoadBusyException(String message) {
        super(message);
    }
}
