package com.ethan.note.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 키워드 검색(BM25)용 토큰 나누기.
 * 한국어는 조사가 붙어("적중률이", "적중률은") 단어 그대로는 안 맞으므로, 한글 덩어리는 통째로와 글자 2개씩(bigram) 둘 다 넣는다.
 */
final class Tokens {

    /** 한글 덩어리 | 영문·숫자 덩어리(76.4 같은 소수 포함) */
    private static final Pattern WORD = Pattern.compile("[가-힣]+|[a-z0-9]+(?:\\.[0-9]+)?");

    private Tokens() {
    }

    static List<String> of(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            boolean hangul = isHangul(w.charAt(0));
            if (hangul && w.length() == 1) {
                continue; // "Redis에"의 "에" 같은 한 글자 조사. 거의 모든 조각에 나와서 점수에 잡음만 더한다
            }
            out.add(w);
            // 2글자 단어의 bigram 은 단어 자신과 같은 글자라서 넣으면 한 번 나온 단어가 두 번으로 센다. 3글자부터 쪼갠다.
            if (w.length() >= 3 && hangul) {
                for (int i = 0; i + 2 <= w.length(); i++) {
                    out.add(w.substring(i, i + 2));
                }
            }
        }
        return out;
    }

    private static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
    }
}
