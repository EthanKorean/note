package com.ethan.note.rag;

/** 다른 작업(모델 준비, 검색 평가)이 실행 중일 때. 409 로 응답한다. */
public class BusyException extends RuntimeException {

    public BusyException(String message) {
        super(message);
    }
}
