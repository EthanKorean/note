package com.ethan.note.rag;

/** 검색 대상 문서 1개(파일 이름과 본문). */
public record Doc(String name, String text) {
}
