package com.ethan.note.rag;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

/** 같은 질문 세트로 청킹 조건 1개 × 검색 방식 여러 개의 검색 품질(hit@k, MRR)과 속도를 잰다. */
@Service
public class EvalRunner {

    private static final int TOP_K = 5;
    private static final int WARMUP = 3;
    private static final int DEFAULT_SIZE = 500;
    private static final int DEFAULT_OVERLAP = 50;
    private static final List<String> ALL_RETRIEVERS = SearchIndex.ORDER;

    /** 화면에서 보내는 조건. size 와 overlap 을 비우면 기본값(500, 50). */
    public record Params(String chunker, Integer size, Integer overlap, List<String> retrievers) {
    }

    public record Miss(String question, String top1) {
    }

    /** 점수는 모두 %(hit)와 0~1(mrr), 시간은 ms. */
    public record Row(String retriever, double hit1, double hit3, double hit5, double mrr, double avgMs, double p95Ms, List<Miss> misses) {
    }

    /**
     * @param unreachable 어떤 조각에도 정답 문구가 통째로 들어 있지 않은 질문 수(조각 경계에서 정답이 잘린 경우)
     * @param truncated   임베딩 입력 한도(512 토큰)를 넘어 뒤가 잘린 조각 수. 이 조각은 벡터 검색이 앞부분만 본다
     * @param saved       test_result 에 저장했는지. 저장하지 못했으면 새로고침하면 사라진다
     */
    public record Result(String chunker, int size, int overlap, int chunks, int avgChars, int questions, int unreachable, int truncated,
                         List<Row> rows, String finishedAt, boolean saved) {
        public Result withSaved(boolean value) {
            return new Result(chunker, size, overlap, chunks, avgChars, questions, unreachable, truncated, rows, finishedAt, value);
        }
    }

    private final IndexService indexes;
    private final EvalQuestions questions;
    private final WorkGuard guard;
    private final RagEvents events;
    private final ResultStore results;

    public EvalRunner(IndexService indexes, EvalQuestions questions, WorkGuard guard, RagEvents events, ResultStore results) {
        this.indexes = indexes;
        this.questions = questions;
        this.guard = guard;
        this.events = events;
        this.results = results;
    }

    public Result run(Params p) {
        ChunkConfig cfg = new ChunkConfig(p.chunker(), p.size() != null ? p.size() : DEFAULT_SIZE,
                p.overlap() != null ? p.overlap() : DEFAULT_OVERLAP);
        List<String> given = p.retrievers() == null ? List.of() : p.retrievers().stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<String> retrievers = given.isEmpty() ? ALL_RETRIEVERS : given;
        for (String r : retrievers) {
            SearchIndex.requireRetriever(r);
        }

        guard.acquire("검색 평가");
        long t0 = System.nanoTime();
        try {
            events.info("eval", "검색 평가 시작: " + cfg.type() + " " + cfg.size() + "자" + (cfg.overlap() > 0 ? " 겹침 " + cfg.overlap() : ""));
            SearchIndex idx = indexes.get(cfg);
            List<EvalQuestions.Question> qs = questions.all();

            int unreachable = 0;
            for (EvalQuestions.Question q : qs) {
                if (idx.chunks().stream().noneMatch(c -> EvalQuestions.relevant(idx.normalized(c.id()), q))) {
                    unreachable++;
                }
            }
            int avgChars = (int) Math.round(idx.chunks().stream().mapToInt(c -> c.text().length()).average().orElse(0));

            List<Row> rows = new ArrayList<>();
            for (String retriever : retrievers) {
                rows.add(measure(idx, qs, retriever));
                events.info("eval", retriever + " 완료");
            }
            Result result = new Result(cfg.type(), cfg.size(), cfg.overlap(), idx.chunks().size(), avgChars, qs.size(), unreachable,
                    idx.truncated(), rows, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), true);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            if (!results.save(cfg.type(), "success", elapsedMs, result)) {
                // 저장에 실패해도 측정은 끝났으니 결과는 돌려주되, 화면이 "저장 안 됨"을 알리도록 표시한다.
                result = result.withSaved(false);
                events.error("eval", "결과를 test_result 에 저장하지 못했습니다(DB 확인). 화면에서만 볼 수 있습니다");
            }
            events.success("eval", "검색 평가 완료 (" + elapsedMs + "ms)");
            return result;
        } catch (RuntimeException e) {
            events.error("eval", "검색 평가 실패: " + PathMask.scrub(e.getMessage())); // 웹소켓으로 나가므로 경로를 가린다
            throw e;
        } finally {
            guard.release();
        }
    }

    private Row measure(SearchIndex idx, List<EvalQuestions.Question> qs, String retriever) {
        int hit1 = 0;
        int hit3 = 0;
        int hit5 = 0;
        double rr = 0;
        List<Double> times = new ArrayList<>();
        List<Miss> misses = new ArrayList<>();
        // 처음 몇 번은 JIT 컴파일 때문에 느리다. 시간에 섞이지 않게 먼저 돌려 둔다.
        for (int i = 0; i < Math.min(WARMUP, qs.size()); i++) {
            idx.search(qs.get(i).q(), retriever, TOP_K);
        }
        for (EvalQuestions.Question q : qs) {
            long s = System.nanoTime();
            List<SearchIndex.Hit> hits = idx.search(q.q(), retriever, TOP_K);
            times.add((System.nanoTime() - s) / 1_000_000.0);

            int rank = 0; // 정답 조각이 처음 나온 순위(1부터). 상위 5개에 없으면 0
            for (int i = 0; i < hits.size(); i++) {
                if (EvalQuestions.relevant(idx.normalized(hits.get(i).chunk().id()), q)) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank == 0) {
                Chunk top = hits.isEmpty() ? null : hits.get(0).chunk();
                misses.add(new Miss(q.q(), top == null ? "(검색 결과 없음)" : top.source() + (top.heading().isEmpty() ? "" : " > " + top.heading())));
                continue;
            }
            rr += 1.0 / rank;
            hit5++;
            if (rank <= 3) {
                hit3++;
            }
            if (rank == 1) {
                hit1++;
            }
        }
        int n = qs.size();
        Collections.sort(times);
        double avg = times.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double p95 = times.isEmpty() ? 0 : times.get(Math.max(0, (int) Math.ceil(times.size() * 0.95) - 1));
        return new Row(retriever, pct(hit1, n), pct(hit3, n), pct(hit5, n), round(rr / Math.max(1, n), 3), round(avg, 2), round(p95, 2), misses);
    }

    private static double pct(int part, int total) {
        return round(part * 100.0 / Math.max(1, total), 1);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
