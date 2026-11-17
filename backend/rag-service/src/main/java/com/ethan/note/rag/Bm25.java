package com.ethan.note.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 키워드 검색(BM25). 라이브러리 없이 공식을 그대로 구현했다. */
final class Bm25 {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final List<Map<String, Integer>> tf = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private final int[] length;
    private final double avgLength;

    Bm25(List<String> texts) {
        length = new int[texts.size()];
        long total = 0;
        for (int i = 0; i < texts.size(); i++) {
            List<String> tokens = Tokens.of(texts.get(i));
            Map<String, Integer> counts = new HashMap<>();
            for (String t : tokens) {
                counts.merge(t, 1, Integer::sum);
            }
            tf.add(counts);
            length[i] = tokens.size();
            total += tokens.size();
            for (String t : counts.keySet()) {
                df.merge(t, 1, Integer::sum);
            }
        }
        avgLength = texts.isEmpty() ? 1 : Math.max(1, (double) total / texts.size());
    }

    /** 조각마다 질문과의 점수. 질문 단어가 하나도 없으면 0. */
    double[] score(String query) {
        int n = tf.size();
        double[] scores = new double[n];
        Set<String> terms = new LinkedHashSet<>(Tokens.of(query));
        for (String term : terms) {
            Integer d = df.get(term);
            if (d == null) {
                continue;
            }
            double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
            for (int i = 0; i < n; i++) {
                Integer f = tf.get(i).get(term);
                if (f != null) {
                    scores[i] += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * length[i] / avgLength));
                }
            }
        }
        return scores;
    }
}
