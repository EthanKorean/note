package com.ethan.note.image;

/** 화면 종류별 이미지 크기. 웹(큰 화면), 앱(작은 화면), 썸네일(목록). */
public enum ImageSize {
    WEB("web", 800, 600),
    APP("app", 400, 300),
    THUMB("thumb", 150, 113);

    public final String key;
    public final int width;
    public final int height;

    ImageSize(String key, int width, int height) {
        this.key = key;
        this.width = width;
        this.height = height;
    }

    public static ImageSize of(String key) {
        for (ImageSize s : values()) {
            if (s.key.equalsIgnoreCase(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 크기: " + key + " (web | app | thumb)");
    }
}
