package com.ethan.note.rag;

import java.util.Set;

/**
 * 청킹 조건.
 *
 * @param type    fixed(고정 길이) | paragraph(문단을 모아 크기까지) | heading(제목 단위, 길면 문단으로 더 쪼갬)
 * @param size    조각 크기(글자 수)
 * @param overlap 앞 조각과 겹치는 글자 수. fixed 에서만 쓰고 다른 방식은 0 으로 본다
 */
public record ChunkConfig(String type, int size, int overlap) {

    public static final Set<String> TYPES = Set.of("fixed", "paragraph", "heading");

    public ChunkConfig {
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("청킹 방식은 fixed, paragraph, heading 중 하나여야 합니다.");
        }
        if (!type.equals("fixed")) {
            overlap = 0; // 겹침은 고정 길이에서만 쓴다. 다른 방식에서는 값을 무시하므로 검사도 하지 않는다
        }
        if (size < 100 || size > 3000) {
            throw new IllegalArgumentException("조각 크기는 100 ~ 3000 글자여야 합니다.");
        }
        if (overlap < 0 || overlap >= size / 2) {
            throw new IllegalArgumentException("겹침은 0 이상, 조각 크기의 절반 미만이어야 합니다.");
        }
    }

    public String key() {
        return type + "|" + size + "|" + overlap;
    }
}
