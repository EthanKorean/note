package com.ethan.note.lock;

/** 사용자가 보낸 값이 잘못된 경우(모르는 실험, 범위 밖의 값). 400 으로 응답한다. 서버 안쪽에서 난 오류와 구분하려고 따로 둔다. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
