package com.ethan.note.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 청킹 조건별 검색 색인을 만들어 두고 재사용한다(같은 조건을 다시 실행해도 임베딩을 다시 하지 않는다).
 *
 * <p>색인을 만들거나 바꾸는 호출(get, reload)은 항상 WorkGuard 를 잡은 상태에서만 온다(검색, 평가, 준비 모두).
 * 그래서 이 클래스는 따로 잠그지 않는다. 상태 조회가 docs() 를 부르므로 docs 만 volatile 로 둔다.
 */
@Service
public class IndexService {

    private static final int MAX_INDEXES = 8;

    private final Embedder embedder;
    private final Chunker chunker;
    private final DocStore docStore;
    private final RagEvents events;

    private final Map<String, SearchIndex> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SearchIndex> eldest) {
            return size() > MAX_INDEXES;
        }
    };
    private volatile List<Doc> docs;
    /** docs 를 바꾸는 순간만 잡는 짧은 잠금. 상태 조회의 첫 읽기가 reload() 의 새 문서를 옛 문서로 덮어쓰지 않게 한다. */
    private final Object docsLock = new Object();

    public IndexService(Embedder embedder, Chunker chunker, DocStore docStore, RagEvents events) {
        this.embedder = embedder;
        this.chunker = chunker;
        this.docStore = docStore;
        this.events = events;
    }

    /** 문서를 다시 읽고 만들어 둔 색인을 버린다. */
    public List<Doc> reload() {
        List<Doc> loaded = docStore.load(); // 파일 읽기는 잠금 밖에서
        synchronized (docsLock) {
            docs = loaded;
        }
        cache.clear();
        return loaded;
    }

    public String docDir() {
        return PathMask.show(docStore.dir());
    }

    public List<Doc> docs() {
        List<Doc> d = docs;
        if (d == null) {
            List<Doc> loaded = docStore.load();
            synchronized (docsLock) {
                if (docs == null) { // 그 사이에 reload() 가 새 문서를 넣었다면 그것을 그대로 둔다
                    docs = loaded;
                }
                d = docs;
            }
        }
        return d;
    }

    public SearchIndex get(ChunkConfig cfg) {
        if (!embedder.isReady()) {
            throw new IllegalArgumentException("모델이 준비되지 않았습니다. 먼저 '모델·문서 준비'를 실행하세요.");
        }
        SearchIndex hit = cache.get(cfg.key());
        if (hit != null) {
            return hit;
        }
        long t0 = System.nanoTime();
        List<Chunk> chunks = chunker.chunk(docs(), cfg);
        events.info("index", "조각 " + chunks.size() + "개를 임베딩하는 중 (" + cfg.type() + ", " + cfg.size() + "자)");
        List<String> texts = chunks.stream().map(Chunk::indexText).toList();
        Embedder.Passages embedded = embedder.embedPassages(texts);
        Bm25 bm25 = new Bm25(texts);
        String[] normalized = chunks.stream().map(c -> EvalQuestions.normalize(c.text())).toArray(String[]::new);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        SearchIndex idx = new SearchIndex(chunks, embedded.vectors().toArray(new float[0][]), bm25, embedder, ms, embedded.truncated(), normalized);
        cache.put(cfg.key(), idx);
        events.info("index", "색인 완료: 조각 " + chunks.size() + "개, " + ms + "ms"
                + (embedded.truncated() > 0 ? " (임베딩 입력 한도를 넘어 뒤가 잘린 조각 " + embedded.truncated() + "개)" : ""));
        return idx;
    }
}
