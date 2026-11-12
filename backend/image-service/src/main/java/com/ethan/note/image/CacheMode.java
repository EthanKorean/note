package com.ethan.note.image;

/** 이미지를 내려 주는 방식. */
public enum CacheMode {
    /** 요청마다 원본을 읽어 크기를 줄여서 응답 */
    NOCACHE,
    /** 업로드(시딩) 때 크기별로 미리 만들어 둔 파일을 읽어서 응답 */
    PREBUILT,
    /** 자주 보는 이미지만 Redis 에 두고, 없으면 미리 만든 파일을 읽는다 */
    REDIS;

    public static CacheMode of(String s) {
        try {
            return valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("지원하지 않는 방식: " + s + " (nocache | prebuilt | redis)");
        }
    }
}
