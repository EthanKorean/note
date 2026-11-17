package com.ethan.note.rag;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 글을 벡터로 바꾼다(임베딩). 별도 설치나 API 키 없이 서비스 안에서 ONNX 모델(multilingual-e5-small)을 직접 돌린다.
 * 모델 파일은 처음 한 번 내려받는다(약 135MB). e5 모델은 질문에 "query: ", 문서에 "passage: " 를 붙여야 한다.
 *
 * <p>글은 항상 하나씩 계산한다. 여러 개를 묶으면 가장 긴 글에 맞춰 패딩이 붙는데, 양자화 모델은 패딩 길이에 따라
 * 결과가 미세하게 달라진다(같은 조건인데 캐시 상태에 따라 MRR 이 0.02~0.03 흔들렸다). 측정 도구는 매번 같은 값을 내야 한다.
 */
@Component
public class Embedder {

    private static final String MODEL_FILE = "onnx/model_quantized.onnx";
    private static final String TOKENIZER_FILE = "tokenizer.json";
    /** 모델의 입력 한도. 이보다 긴 글은 뒤가 잘려서 벡터에 반영되지 않는다. */
    private static final int MAX_TOKENS = 512;
    private static final int MAX_CACHE = 5000;
    /** 내려받기가 이 시간 안에 끝나지 않으면 중단한다(연결이 멈춘 채 잠금을 쥐고 있지 않게). */
    private static final int DOWNLOAD_TIMEOUT_MIN = 15;
    /** 이보다 작으면 모델/토크나이저가 아니라 오류 페이지를 받은 것이다(주소가 틀린 경우 등). */
    private static final long MIN_MODEL_BYTES = 50_000_000L;
    private static final long MIN_TOKENIZER_BYTES = 1_000_000L;

    private final Path dir;
    private final String baseUrl;
    private final RagEvents events;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private boolean needsTypeIds;
    private volatile boolean ready;
    /** load() 끼리만 서로 기다리게 하는 잠금. run(), close() 가 쓰는 this 와 다르다(다운로드 동안 서버 종료가 막히지 않게). */
    private final Object loadLock = new Object();

    /** 한 글의 임베딩 결과. truncated 는 입력 한도(512 토큰)에 걸려 뒤가 잘렸다는 뜻. */
    private record Embedded(float[] vector, boolean truncated) {
    }

    /** 같은 글은 다시 계산하지 않는다(청킹 조건을 바꿔도 겹치는 조각이 많다). 질문은 넣지 않는다. */
    private final Map<String, Embedded> cache = new ConcurrentHashMap<>();

    public Embedder(@Value("${rag.model-dir}") String dir, @Value("${rag.model-url}") String baseUrl, RagEvents events) {
        // .env 에서 비워 둔 값("")은 설정 파일의 기본값이 적용되지 않으므로 여기서 기본값으로 대체한다.
        this.dir = Path.of(dir.isBlank() ? "../../rag-data/model" : dir).toAbsolutePath().normalize();
        String url = baseUrl.isBlank() ? "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main" : baseUrl;
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.events = events;
    }

    public boolean isReady() {
        return ready;
    }

    public Path dir() {
        return dir;
    }

    /** 내려받은 모델 파일의 크기(바이트). 모델 본체가 없으면 0(토크나이저만 받은 상태는 "받음"으로 치지 않는다). */
    public long modelBytes() {
        try {
            Path model = dir.resolve(MODEL_FILE);
            if (!Files.isRegularFile(model)) {
                return 0;
            }
            long total = Files.size(model);
            Path tok = dir.resolve(TOKENIZER_FILE);
            return Files.isRegularFile(tok) ? total + Files.size(tok) : total;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 모델 파일을 내려받고(없을 때만) 메모리에 올린다. 이미 올라가 있으면 아무것도 하지 않는다. */
    public void load() throws Exception {
        synchronized (loadLock) {
            if (ready) {
                return;
            }
            download(TOKENIZER_FILE); // 오래 걸리는 일이라 this 잠금을 잡지 않는다(서버 종료의 close() 가 기다리지 않게)
            download(MODEL_FILE);
            synchronized (this) {
                openModel();
            }
        }
    }

    private void openModel() throws Exception {
        events.info("model", "모델을 메모리에 올리는 중");
        long t0 = System.nanoTime();
        HuggingFaceTokenizer tok = null;
        OrtSession s = null;
        try {
            // 토크나이저의 자르기 기능은 쓰지 않는다. 자르면 "정확히 한도에 맞는 길이"와 "잘린 것"을 구별할 수 없어서,
            // 전체 길이를 받아 직접 자른다(run 참고).
            tok = HuggingFaceTokenizer.builder().optTokenizerPath(dir.resolve(TOKENIZER_FILE)).optTruncation(false).build();
            OrtEnvironment e = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
                s = e.createSession(dir.resolve(MODEL_FILE).toString(), opts);
            }
            needsTypeIds = s.getInputNames().contains("token_type_ids");
            env = e;
            session = s;
            tokenizer = tok;
        } catch (Exception | Error ex) {
            // 실패하면 이미 만든 네이티브 자원(토크나이저, 세션)을 돌려준다. 다시 눌러도 쌓이지 않게 한다.
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // 이미 실패한 중이라 무시한다
                }
            }
            if (tok != null) {
                tok.close();
            }
            if (tok == null && ex instanceof java.io.IOException) {
                // 토크나이저 파일을 못 읽었다(깨진 파일). 남겨 두면 다시 눌러도 "이미 있다"며 건너뛰고 같은 곳에서 또 실패한다.
                Files.deleteIfExists(dir.resolve(TOKENIZER_FILE));
                throw new IllegalStateException("토크나이저 파일이 깨진 것 같아 지웠습니다. 다시 누르면 새로 받습니다", ex);
            }
            if (ex instanceof OrtException) {
                // 모델 파일이 깨진 경우다. 남겨 두면 다시 눌러도 "이미 있다"며 건너뛰고 같은 곳에서 또 실패한다. 지워서 새로 받게 한다.
                // (네이티브 라이브러리를 못 받는 등 다른 원인일 때는 파일이 멀쩡하므로 지우지 않는다.)
                Files.deleteIfExists(dir.resolve(MODEL_FILE));
                Files.deleteIfExists(dir.resolve(TOKENIZER_FILE));
                // 실패 알림(웹소켓 이벤트)은 부른 쪽(컨트롤러)이 한 번만 보낸다. 여기서는 설명을 담아 던지기만 한다.
                throw new IllegalStateException("모델 파일이 깨진 것 같아 지웠습니다. 다시 누르면 새로 받습니다", ex);
            }
            throw ex;
        }
        ready = true;
        events.success("model", "모델 준비 완료 (" + (System.nanoTime() - t0) / 1_000_000 + "ms)");
    }

    @PreDestroy
    public synchronized void close() {
        ready = false;
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignored) {
            // 종료 중이라 무시한다
        }
        if (tokenizer != null) {
            tokenizer.close();
        }
    }

    /** 질문은 캐시하지 않는다. 검색 시간에 질문 임베딩 비용이 항상 들어가야 방식끼리 시간을 공정하게 비교할 수 있다. */
    public float[] embedQuery(String query) {
        requireReady();
        return run("query: " + query).vector();
    }

    /**
     * @param truncated 임베딩 입력 한도(512 토큰)를 넘어서 뒤쪽이 잘린 채 벡터가 만들어진 글의 수. 잘린 글의 벡터는 앞부분만
     *                  반영하므로, 긴 조각을 쓰는 평가 결과를 읽을 때 알아야 한다.
     */
    public record Passages(List<float[]> vectors, int truncated) {
    }

    /** 임베딩을 계산하면서 잘림 수도 같은 반복에서 센다(캐시가 비워져도 값이 어긋나지 않게). */
    public Passages embedPassages(List<String> texts) {
        requireReady();
        List<float[]> vectors = new ArrayList<>(texts.size());
        int truncated = 0;
        for (String t : texts) {
            Embedded e = passage(t);
            vectors.add(e.vector());
            if (e.truncated()) {
                truncated++;
            }
        }
        return new Passages(vectors, truncated);
    }

    private Embedded passage(String text) {
        String key = "passage: " + text;
        Embedded hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Embedded e = run(key);
        if (cache.size() >= MAX_CACHE) {
            cache.clear(); // 상한을 넘으면 통째로 비운다(문서가 몇 개뿐인 실험이라 단순하게)
        }
        cache.put(key, e);
        return e;
    }

    private void requireReady() {
        if (!ready) {
            throw new IllegalArgumentException("모델이 준비되지 않았습니다. 먼저 '모델·문서 준비'를 실행하세요.");
        }
    }

    /** 토크나이저 → 모델 → 토큰 벡터의 평균 → 길이 1로 정규화. */
    private synchronized Embedded run(String prefixed) {
        long[] ids = tokenizer.encode(prefixed).getIds();
        // 한도(512)를 넘으면 앞 511개와 끝의 특수 토큰(</s>)만 남긴다. 토크나이저의 자르기와 같은 결과다.
        boolean truncated = ids.length > MAX_TOKENS;
        if (truncated) {
            long last = ids[ids.length - 1];
            ids = Arrays.copyOf(ids, MAX_TOKENS);
            ids[MAX_TOKENS - 1] = last;
        }
        long[] mask = new long[ids.length]; // 한 글씩 계산하므로 패딩이 없다. 전부 1
        Arrays.fill(mask, 1L);
        List<OnnxTensor> opened = new ArrayList<>();
        try {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            OnnxTensor tIds = OnnxTensor.createTensor(env, new long[][] {ids});
            opened.add(tIds);
            OnnxTensor tMask = OnnxTensor.createTensor(env, new long[][] {mask});
            opened.add(tMask);
            inputs.put("input_ids", tIds);
            inputs.put("attention_mask", tMask);
            if (needsTypeIds) {
                OnnxTensor tTypes = OnnxTensor.createTensor(env, new long[1][ids.length]);
                opened.add(tTypes);
                inputs.put("token_type_ids", tTypes);
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                return new Embedded(pool(hidden[0], mask), truncated);
            }
        } catch (Exception e) {
            throw new IllegalStateException("임베딩 계산에 실패했습니다: " + e.getMessage(), e);
        } finally {
            for (OnnxTensor t : opened) {
                t.close();
            }
        }
    }

    private static float[] pool(float[][] tokens, long[] mask) {
        int dim = tokens[0].length;
        float[] v = new float[dim];
        int count = 0;
        for (int t = 0; t < tokens.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            count++;
            for (int h = 0; h < dim; h++) {
                v[h] += tokens[t][h];
            }
        }
        double norm = 0;
        for (int h = 0; h < dim; h++) {
            v[h] /= Math.max(1, count);
            norm += (double) v[h] * v[h];
        }
        float inv = (float) (1.0 / Math.max(1e-12, Math.sqrt(norm)));
        for (int h = 0; h < dim; h++) {
            v[h] *= inv;
        }
        return v;
    }

    private void download(String rel) throws Exception {
        Path target = dir.resolve(rel);
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        events.info("model", rel + " 내려받는 중");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/" + rel)).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IllegalStateException(rel + " 내려받기 실패: HTTP " + res.statusCode());
        }
        long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        InputStream in = res.body();
        // HttpRequest 의 timeout 은 응답 머리말까지만 재므로, 본문을 받는 도중에 연결이 멈추면 read 가 끝없이 기다린다.
        // 받는 일을 따로 돌리고 전체 제한 시간을 둔다.
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "model-download");
            th.setDaemon(true); // 서버 종료를 붙잡지 않게
            return th;
        });
        Future<Long> copy = pool.submit(() -> {
            long done = 0;
            int lastPct = -1;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    done += r;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct >= 0 && pct / 20 != lastPct / 20) {
                        lastPct = pct;
                        events.info("model", rel + " " + pct + "% (" + done / 1024 / 1024 + "MB)");
                    }
                }
            }
            return done;
        });
        long done;
        try {
            done = copy.get(DOWNLOAD_TIMEOUT_MIN, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            copy.cancel(true);
            in.close();
            Files.deleteIfExists(part);
            throw new IllegalStateException(rel + " 내려받기가 " + DOWNLOAD_TIMEOUT_MIN + "분 안에 끝나지 않아 중단했습니다");
        } catch (ExecutionException e) {
            in.close();
            Files.deleteIfExists(part);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(rel + " 내려받기 실패: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 중단 요청을 삼키지 않는다
            in.close();
            Files.deleteIfExists(part);
            throw e;
        } finally {
            pool.shutdownNow();
        }
        in.close();
        long min = rel.equals(MODEL_FILE) ? MIN_MODEL_BYTES : MIN_TOKENIZER_BYTES;
        if ((total > 0 && done != total) || done < min) {
            Files.deleteIfExists(part);
            throw new IllegalStateException(rel + " 내려받은 파일이 올바르지 않습니다(" + done + " 바이트). 중간에 끊겼거나 주소가 틀렸을 수 있습니다");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
