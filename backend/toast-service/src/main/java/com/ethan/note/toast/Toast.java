package com.ethan.note.toast;

/** 브라우저로 나가는 알림 (프론트 stores/toast.ts 의 ToastEvent 와 대응). kind: done | attention | error | info */
public record Toast(String id, String kind, String title, String message, String project, String time) {
}
