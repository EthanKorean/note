package com.ethan.note.image;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/image")
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    private final ImageStore store;
    private final ImageCacheService service;
    private final BenchRunner runner;
    private final ResultStore results;
    private final ReplaceRunner replacer;

    public ImageController(ImageStore store, ImageCacheService service, BenchRunner runner, ResultStore results, ReplaceRunner replacer) {
        this.replacer = replacer;
        this.store = store;
        this.service = service;
        this.runner = runner;
        this.results = results;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", store.count());
        m.put("dir", PathMask.show(store.dir()));
        m.put("running", runner.isRunning());
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (ImageSize s : ImageSize.values()) {
            bytes.put(s.key, store.totalBytes(s));
        }
        m.put("bytesBySize", bytes);
        return m;
    }

    /** 상품 1..count 의 이미지를 만든다. source=draw 는 직접 그리기, web 은 샘플 사진 받기. force=true 면 이미 있어도 다시 만든다. */
    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "1000") int count,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    @RequestParam(defaultValue = "draw") String source) throws IOException {
        if (count < 1 || count > 100_000) {
            throw new IllegalArgumentException("count는 1~100000");
        }
        long t0 = System.nanoTime();
        if (!"draw".equals(source) && !"web".equals(source)) {
            throw new IllegalArgumentException("source는 draw | web");
        }
        int made = "web".equals(source) ? store.seedFromWeb(count, force) : store.seed(count, force);
        return Map.of("made", made, "total", store.count(), "ms", (System.nanoTime() - t0) / 1_000_000);
    }

    /** 이미지를 내려 준다(브라우저/앱이 직접 부르는 주소). 응답 헤더 X-Cache 로 적중 여부를 알 수 있다. */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> image(@PathVariable int id,
                                        @RequestParam(defaultValue = "web") String size,
                                        @RequestParam(defaultValue = "redis") String mode) throws IOException {
        ImageSize sz = ImageSize.of(size);
        // 캐시 적중에서도 디스크를 건드리지 않도록 요청한 크기 파일만 확인한다(nocache 는 원본도).
        if (!store.exists(id, sz) || (CacheMode.of(mode) == CacheMode.NOCACHE && !store.complete(id))) {
            return ResponseEntity.notFound().build();
        }
        ImageCacheService.Fetch f = service.fetchForServing(id, sz, mode);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noCache())
                .header("X-Cache", f.hit() ? "HIT" : "MISS")
                .body(f.bytes());
    }

    @PostMapping("/bench")
    public BenchResult bench(@RequestBody BenchParams p) throws Exception {
        long t0 = System.nanoTime();
        try {
            BenchResult r = runner.run(p);
            results.save(p.mode(), "success", r.elapsedMs(), r);
            return r;
        } catch (BusyException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("실험 실패", e);
            results.save(p.mode(), "fail", (System.nanoTime() - t0) / 1_000_000, Map.of("error", String.valueOf(e.getMessage()), "params", p));
            throw e;
        }
    }

    /** 이미지 교체 실험: 캐시에 올린 이미지를 바꾸고 무효화 방식별로 옛 이미지가 나가는지 본다. */
    @PostMapping("/replace-test")
    public ReplaceResult replaceTest(@RequestParam(defaultValue = "purge") String strategy,
                                     @RequestParam(defaultValue = "100") int items,
                                     @RequestParam(defaultValue = "300") int ttlSec) throws Exception {
        long t0 = System.nanoTime();
        try {
            ReplaceResult r = replacer.run(strategy, items, ttlSec);
            results.save("image-replace", strategy, "success", (System.nanoTime() - t0) / 1_000_000, r);
            return r;
        } catch (BusyException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            results.save("image-replace", strategy, "fail", (System.nanoTime() - t0) / 1_000_000,
                    Map.of("error", String.valueOf(e.getMessage()), "strategy", strategy));
            throw e;
        }
    }

    /** 이 실험이 쓰는 Redis 키를 지운다. */
    @PostMapping("/cache/clear")
    public Map<String, Object> clear() {
        return Map.of("deleted", runner.clearCache());
    }

    @ExceptionHandler(BusyException.class)
    public ResponseEntity<Map<String, String>> busy(BusyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", PathMask.scrub(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", PathMask.scrub(String.valueOf(e.getMessage()))));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> unreadable(Exception e) {
        Throwable root = e.getCause() != null ? e.getCause() : e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return ResponseEntity.badRequest().body(Map.of("error", PathMask.scrub(String.valueOf(root.getMessage()))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        // 스프링이 만든 4xx(타입 불일치, 405 등)는 그대로 돌려준다. 500 으로 덮어쓰지 않는다.
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", PathMask.scrub(String.valueOf(e.getMessage()))));
        }
        if (e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
            return ResponseEntity.badRequest().body(Map.of("error", "잘못된 값입니다: " + e.getMessage()));
        }
        log.error("요청 실패", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", PathMask.scrub(String.valueOf(e.getMessage()))));
    }
}
