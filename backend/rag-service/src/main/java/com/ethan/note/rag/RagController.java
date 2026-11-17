package com.ethan.note.rag;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);
    private static final int MAX_TEXT = 1500;

    public record DocInfo(String name, int chars) {
    }

    public record Status(boolean modelReady, long modelBytes, String modelDir, String docsDir, List<DocInfo> docs, int questions,
                         List<String> unmatched, String error, boolean running, String current) {
    }

    public record HitView(int rank, double score, String source, String heading, String text) {
    }

    public record SearchResult(String retriever, String chunker, int chunks, double ms, List<HitView> hits) {
    }

    private final Embedder embedder;
    private final IndexService indexes;
    private final EvalRunner runner;
    private final EvalQuestions questions;
    private final WorkGuard guard;
    private final RagEvents events;

    public RagController(Embedder embedder, IndexService indexes, EvalRunner runner, EvalQuestions questions, WorkGuard guard, RagEvents events) {
        this.events = events;
        this.embedder = embedder;
        this.indexes = indexes;
        this.runner = runner;
        this.questions = questions;
        this.guard = guard;
    }

    @GetMapping("/status")
    public Status status() {
        List<DocInfo> docs = List.of();
        List<String> unmatched = List.of();
        String error = null;
        try {
            List<Doc> loaded = indexes.docs();
            docs = loaded.stream().map(d -> new DocInfo(d.name(), d.text().length())).toList();
            unmatched = questions.unmatched(loaded);
        } catch (RuntimeException e) {
            error = PathMask.scrub(e.getMessage());
        }
        String current = guard.current(); // 한 번만 읽는다(두 번 읽는 사이에 작업이 끝나면 running 과 current 가 어긋난다)
        return new Status(embedder.isReady(), embedder.modelBytes(), PathMask.show(embedder.dir()), docsDir(), docs,
                questions.all().size(), unmatched, error, current != null, current);
    }

    /** 모델을 내려받아 올리고(처음 한 번), 문서를 다시 읽는다. 처음에는 내려받느라 1~2분 걸린다. */
    @PostMapping("/prepare")
    public Status prepare() throws Exception {
        guard.acquire("모델·문서 준비");
        try {
            embedder.load();
            indexes.reload();
        } catch (Exception e) {
            log.error("모델·문서 준비 실패", e);
            events.error("model", "준비 실패: " + PathMask.scrub(e.getMessage()));
            throw e;
        } finally {
            guard.release();
        }
        return status();
    }

    @GetMapping("/search")
    public SearchResult search(@RequestParam String q,
                               @RequestParam(defaultValue = "hybrid") String retriever,
                               @RequestParam(defaultValue = "heading") String chunker,
                               @RequestParam(defaultValue = "500") int size,
                               @RequestParam(defaultValue = "50") int overlap,
                               @RequestParam(defaultValue = "5") int topK) {
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("결과 수는 1 ~ 20 이어야 합니다.");
        }
        // 잘못된 입력은 색인을 만들기 전에(전역 잠금을 잡기 전에) 거절한다. 그렇지 않으면 빈 질문 하나가 색인 임베딩에
        // 몇 초를 쓰고, 그동안 다른 작업을 막고, 색인 캐시를 채운다.
        if (q.isBlank()) {
            throw new IllegalArgumentException("질문이 비어 있습니다.");
        }
        SearchIndex.requireRetriever(retriever);
        ChunkConfig cfg = new ChunkConfig(chunker, size, overlap);
        // 검색 평가가 도는 동안 끼어들면 평가의 시간 측정이 흔들리므로, 다른 작업과 겹치면 거절한다.
        guard.acquire("질문 검색");
        try {
            SearchIndex idx = indexes.get(cfg);
            long t0 = System.nanoTime();
            List<SearchIndex.Hit> hits = idx.search(q, retriever, topK);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            List<HitView> views = new java.util.ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                Chunk c = hits.get(i).chunk();
                String text = c.text().length() > MAX_TEXT ? c.text().substring(0, MAX_TEXT) + "…" : c.text();
                views.add(new HitView(i + 1, Math.round(hits.get(i).score() * 10000) / 10000.0, c.source(), c.heading(), text));
            }
            return new SearchResult(retriever, chunker, idx.chunks().size(), Math.round(ms * 100) / 100.0, views);
        } finally {
            guard.release();
        }
    }

    @PostMapping("/eval")
    public EvalRunner.Result eval(@RequestBody EvalRunner.Params params) {
        return runner.run(params);
    }

    private String docsDir() {
        try {
            return indexes.docDir();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @ExceptionHandler(BusyException.class)
    public ResponseEntity<Map<String, String>> busy(BusyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", PathMask.scrub(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", PathMask.scrub(String.valueOf(e.getMessage()))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return ResponseEntity.badRequest().body(Map.of("error", PathMask.scrub(String.valueOf(root.getMessage()))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        // 스프링이 만든 4xx(필수 파라미터 누락, 타입 불일치, 405 등)는 그대로 돌려준다. 500 으로 덮어쓰지 않는다.
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", PathMask.scrub(String.valueOf(e.getMessage()))));
        }
        if (e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
            return ResponseEntity.badRequest().body(Map.of("error", "잘못된 값입니다: " + PathMask.scrub(e.getMessage())));
        }
        log.error("요청 처리 실패", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", PathMask.scrub(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
    }
}
