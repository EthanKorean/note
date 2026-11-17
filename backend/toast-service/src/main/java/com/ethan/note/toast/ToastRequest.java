package com.ethan.note.toast;

/** 훅이 보내는 본문. 모두 선택이며 비어 있으면 서버가 기본값을 채운다. */
public record ToastRequest(String kind, String title, String message, String project) {
}
