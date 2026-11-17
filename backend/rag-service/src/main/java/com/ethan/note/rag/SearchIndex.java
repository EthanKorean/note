package com.ethan.note.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 한 가지 청킹 조건으로 만든 검색 색인: 조각, 조각 벡터, 키워드 색인. */
public final class SearchIndex {

    /** 검색 방식 목록(화면에 보이는 순서). 새 방식을 더할 때 고칠 곳은 여기와 search() 의 switch 뿐이다. */
    public static final List<String> ORDER = List.of("vector", "bm25", "hybrid");
    public static final Set<String> RETRIEVERS = Set.copyOf(ORDER);
    /** RRF 상수. 순위가 낮은 항목의 영향을 줄이는 값으로 흔히 60을 쓴다. */
    private static final int RRF_K = 60;
    /** 하이브리드가 각 검색에서 가져오는 후보 수. */
    private static final int HYBRID_DEPTH = 50;

    public record Hit(Chunk chunk, double score) {
    }

    private final List<Chunk> chunks;
    private final float[][] vectors;
    private final Bm25 bm25;
    private final Embedder embedder;
    private final long buildMs;
    private final int truncated;
    private final String[] normalized;

    SearchIndex(List<Chunk> chunks, float[][] vectors, Bm25 bm25, Embedder embedder, long buildMs, int truncated, String[] normalized) {
        this.chunks = chunks;
        this.vectors = vectors;
        this.bm25 = bm25;
        this.embedder = embedder;
        this.buildMs = buildMs;
        this.truncated = truncated;
        this.normalized = normalized;
    }

    /** 검색 방식 이름 검증(목록은 ORDER 한 곳에서만 고친다). */
    public static void requireRetriever(String retriever) {
        if (retriever == null || !RETRIEVERS.contains(retriever)) {
            throw new IllegalArgumentException("검색 방식은 " + String.join(", ", ORDER) + " 중 하나여야 합니다.");
        }
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    /** 임베딩 입력 한도(512 토큰)를 넘어 뒤쪽이 잘린 채로 벡터가 만들어진 조각 수. 이 조각은 벡터 검색이 앞부분만 본다. */
    public int truncated() {
        return truncated;
    }

    /** 공백을 정리한 조각 원문(정답 문구 판정용). 판정할 때마다 정규식을 돌리지 않으려고 색인을 만들 때 한 번 계산해 둔다. */
    public String normalized(int chunkId) {
        return normalized[chunkId];
    }

    public long buildMs() {
        return buildMs;
    }

    public List<Hit> search(String query, String retriever, int k) {
        requireRetriever(retriever);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("질문이 비어 있습니다.");
        }
        return switch (retriever) {
            case "vector" -> top(vectorScores(query), k, false);
            case "bm25" -> top(bm25.score(query), k, true);
            default -> hybrid(query, k);
        };
    }

    private double[] vectorScores(String query) {
        float[] q = embedder.embedQuery(query);
        double[] scores = new double[vectors.length];
        for (int i = 0; i < vectors.length; i++) {
            double dot = 0; // 둘 다 길이 1이라 내적이 곧 코사인 유사도
            float[] v = vectors[i];
            for (int h = 0; h < q.length; h++) {
                dot += (double) q[h] * v[h];
            }
            scores[i] = dot;
        }
        return scores;
    }

    private List<Hit> hybrid(String query, int k) {
        List<Hit> byVector = top(vectorScores(query), HYBRID_DEPTH, false);
        List<Hit> byBm25 = top(bm25.score(query), HYBRID_DEPTH, true);
        Map<Integer, Double> fused = new HashMap<>();
        for (List<Hit> list : List.of(byVector, byBm25)) {
            for (int rank = 0; rank < list.size(); rank++) {
                fused.merge(list.get(rank).chunk().id(), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        List<Hit> out = new ArrayList<>();
        fused.forEach((id, score) -> out.add(new Hit(chunks.get(id), score)));
        out.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparingInt(h -> h.chunk().id()));
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    /** 점수가 높은 순으로 k개. positiveOnly 이면 점수가 0 이하인 조각은 제외한다(키워드가 하나도 안 맞은 것). */
    private List<Hit> top(double[] scores, int k, boolean positiveOnly) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            if (!positiveOnly || scores[i] > 0) {
                order.add(i);
            }
        }
        order.sort(Comparator.<Integer>comparingDouble(i -> scores[i]).reversed().thenComparingInt(i -> i));
        List<Hit> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, order.size()); i++) {
            out.add(new Hit(chunks.get(order.get(i)), scores[order.get(i)]));
        }
        return out;
    }
}
