package com.ethan.note.juso.loader;

/** 적재 방식 하나. name()이 API의 {method} 경로 값이 된다. */
public interface JusoLoader {

    String name();

    /** 테이블을 비우기 전에 옵션이 이 방식에 맞는지 검사한다(잘못된 요청이 데이터를 지우지 않도록). */
    default void validate(LoadContext ctx) {
    }

    void load(LoadContext ctx) throws Exception;
}
