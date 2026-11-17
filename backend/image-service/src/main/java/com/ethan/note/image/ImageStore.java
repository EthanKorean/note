package com.ethan.note.image;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 디스크에 둔 상품 이미지. 원본(1200x900)과 크기별로 미리 줄여 둔 파일을 다룬다.
 * 이미지는 서버가 번호와 색으로 직접 그려서 만든다(저작권 문제 없음).
 */
@Component
public class ImageStore {

    private static final Logger log = LoggerFactory.getLogger(ImageStore.class);
    private static final int ORIG_W = 1200;
    private static final int ORIG_H = 900;
    private static volatile boolean fontWarned;

    private final Path dir;
    private final WorkGuard guard;
    private final ImageEvents events;
    private static final long STATS_TTL_MS = 10_000;
    private volatile long statsAt;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private volatile long cachedCount;
    private final java.util.Map<String, Long> cachedBytes = new java.util.concurrent.ConcurrentHashMap<>();

    private final String sampleUrlTemplate;
    private final int downloadThreads;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();

    public ImageStore(@Value("${image.dir}") String dir, WorkGuard guard, ImageEvents events,
                      @Value("${image.sample.url-template}") String sampleUrlTemplate,
                      @Value("${image.sample.threads}") int downloadThreads) {
        this.guard = guard;
        this.events = events;
        this.sampleUrlTemplate = sampleUrlTemplate;
        this.downloadThreads = downloadThreads;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** 상품 1..count 의 이미지를 서버가 직접 그려서 만든다(이미 있으면 건너뛴다). 만든 개수를 돌려준다. */
    public int seed(int count, boolean force) throws IOException {
        return seedFrom("이미지 만들기", count, force, 0, id -> encode(draw(id, 0)));
    }

    /** 상품 1..count 의 원본을 무료 사진 사이트에서 받아 와서 크기별로 줄인다(이미 있으면 건너뛴다). */
    public int seedFromWeb(int count, boolean force) throws IOException {
        return seedFrom("샘플 사진 받기", count, force, Math.max(1, downloadThreads), this::download);
    }

    /** 원본 JPEG 바이트를 돌려주는 방법. */
    private interface Source {
        byte[] original(int id) throws IOException;
    }

    /**
     * @param parallelism 0 이면 공용 풀(CPU 작업), 1 이상이면 그 개수의 전용 스레드(네트워크 대기 작업)
     */
    private int seedFrom(String what, int count, boolean force, int parallelism, Source source) throws IOException {
        guard.acquire(what);
        long t0 = System.nanoTime();
        try {
            Files.createDirectories(dir.resolve("orig"));
            for (ImageSize s : ImageSize.values()) {
                Files.createDirectories(dir.resolve(s.key));
            }
            java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> firstError = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.function.IntPredicate one = id -> {
                try {
                    if (!force && complete(id)) {
                        return false;
                    }
                    byte[] origBytes = source.original(id);
                    BufferedImage orig = ImageIO.read(new ByteArrayInputStream(origBytes));
                    if (orig == null) {
                        throw new IOException("이미지가 아닌 응답입니다: 상품 " + id);
                    }
                    for (ImageSize s : ImageSize.values()) {
                        writeAtomic(file(s.key, id), encode(scale(orig, s.width, s.height)));
                    }
                    // 원본을 마지막에 쓴다: 원본이 있으면 나머지 크기도 모두 있다는 뜻이 된다.
                    writeAtomic(file("orig", id), origBytes);
                    return true;
                } catch (IOException e) {
                    failed.incrementAndGet();
                    firstError.compareAndSet(null, PathMask.scrub(e.getMessage())); // 웹소켓·응답으로 나가므로 경로를 가린다
                    return false;
                }
            };
            events.info(what, count + "개 시작");
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
            int step = Math.max(1, count / 10);
            java.util.function.IntPredicate counted = id -> {
                boolean r = one.test(id);
                int d = done.incrementAndGet();
                if (d % step == 0 && d < count) {
                    events.info(what, "진행 " + d + "/" + count);
                }
                return r;
            };
            int made;
            if (parallelism == 0) {
                made = (int) IntStream.rangeClosed(1, count).parallel().filter(counted).count();
            } else {
                java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(parallelism);
                try {
                    made = pool.submit(() -> IntStream.rangeClosed(1, count).parallel().filter(counted).count()).get().intValue();
                } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("중단됨", e);
                } finally {
                    pool.shutdown();
                }
            }
            log.info("{}: {}개 생성, 실패 {} ({} ms, 폴더 {})", what, made, failed.get(), (System.nanoTime() - t0) / 1_000_000, dir);
            if (failed.get() > 0) {
                events.error(what, made + "개 완료, " + failed.get() + "개 실패: " + firstError.get());
                throw new IOException(made + "개는 만들었지만 " + failed.get() + "개가 실패했습니다: " + firstError.get()
                        + " (다시 누르면 실패한 것만 이어서 받습니다)");
            }
            events.success(what, "완료: " + made + "개 (" + (System.nanoTime() - t0) / 1_000_000 + "ms)");
            return made;
        } finally {
            invalidateStats();   // 잠금을 풀기 전에 갱신해야 바로 시작한 실험이 낡은 개수를 보지 않는다
            guard.release();
        }
    }

    /** image.sample.url-template 의 {n} 을 상품 번호로 바꿔 받는다. 실패하면 두 번까지 다시 시도한다. */
    private byte[] download(int id) throws IOException {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(sampleUrlTemplate.replace("{n}", Integer.toString(id))))
                .timeout(java.time.Duration.ofSeconds(30)).GET().build();
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                java.net.http.HttpResponse<byte[]> res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 200) {
                    return res.body();
                }
                last = new IOException("HTTP " + res.statusCode() + " (상품 " + id + ")");
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("중단됨", e);
            }
        }
        throw last;
    }

    private final java.util.concurrent.atomic.AtomicInteger replaceSeq = new java.util.concurrent.atomic.AtomicInteger();

    /** 상품 이미지를 다른 그림으로 바꾼다(상품 사진을 새로 올린 상황). 실험 잠금 안에서만 부른다. */
    public void replace(int id) throws IOException {
        int variant = replaceSeq.incrementAndGet();
        BufferedImage orig = draw(id, variant);
        for (ImageSize s : ImageSize.values()) {
            writeAtomic(file(s.key, id), encode(scale(orig, s.width, s.height)));
        }
        writeAtomic(file("orig", id), encode(orig));
    }

    /** 이 상품의 파일(원본 + 크기별)이 모두 있는지. */
    public boolean complete(int id) {
        if (!Files.exists(file("orig", id))) {
            return false;
        }
        for (ImageSize s : ImageSize.values()) {
            if (!Files.exists(file(s.key, id))) {
                return false;
            }
        }
        return true;
    }

    public boolean exists(int id, ImageSize size) {
        return Files.exists(file(size.key, id));
    }

    /** 임시 파일에 쓴 뒤 옮겨서, 읽는 쪽이 반쯤 쓰인 파일을 보지 않게 한다. */
    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private void invalidateStats() {
        generation.incrementAndGet();
        statsAt = 0;
    }

    /** 크기가 큰 폴더를 매번 훑지 않도록 몇 초간 합계를 재사용한다(이미지를 만들면 바로 갱신). */
    private void refreshStatsIfStale() {
        if (System.currentTimeMillis() - statsAt < STATS_TTL_MS) {
            return;
        }
        synchronized (cachedBytes) {
            if (System.currentTimeMillis() - statsAt < STATS_TTL_MS) {
                return;
            }
            long gen = generation.get();
            cachedCount = countJpg(dir.resolve("orig"));
            for (ImageSize s : ImageSize.values()) {
                cachedBytes.put(s.key, sumBytes(dir.resolve(s.key)));
            }
            // 훑는 동안 이미지를 새로 만들었다면(무효화됨) 이 합계는 낡았으므로 유효 시간을 주지 않는다.
            statsAt = generation.get() == gen ? System.currentTimeMillis() : 0;
        }
    }

    /** 지금 만들어 둔 상품 수(원본 jpg 파일 기준. 만들다 만 .tmp 는 세지 않는다). */
    public long count() {
        refreshStatsIfStale();
        return cachedCount;
    }

    public long totalBytes(ImageSize size) {
        refreshStatsIfStale();
        return cachedBytes.getOrDefault(size.key, 0L);
    }

    private static long countJpg(Path d) {
        try (var s = Files.list(d)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".jpg")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sumBytes(Path d) {
        try (var s = Files.list(d)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".jpg")).mapToLong(f -> f.toFile().length()).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 1..products 중 파일이 빠진 첫 상품 번호. 모두 있으면 0. */
    public int firstIncomplete(int products) {
        for (int id = 1; id <= products; id++) {
            if (!complete(id)) {
                return id;
            }
        }
        return 0;
    }

    /** 미리 줄여 둔 파일. */
    public byte[] readPrebuilt(int id, ImageSize size) throws IOException {
        return Files.readAllBytes(file(size.key, id));
    }

    /** 원본을 읽어 요청 크기로 줄인다(캐시 없음 방식). */
    public byte[] resizeFromOriginal(int id, ImageSize size) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(file("orig", id))));
        if (src == null) {
            throw new IOException("원본 이미지를 읽을 수 없습니다(손상): 상품 " + id);
        }
        return encode(scale(src, size.width, size.height));
    }

    private Path file(String sub, int id) {
        return dir.resolve(sub).resolve(id + ".jpg");
    }

    private static BufferedImage draw(int id, int variant) {
        BufferedImage img = new BufferedImage(ORIG_W, ORIG_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float hue = ((id + variant * 37) * 0.618034f) % 1f;
            Color a = Color.getHSBColor(hue, 0.55f, 0.95f);
            Color b = Color.getHSBColor((hue + 0.12f) % 1f, 0.75f, 0.55f);
            g.setPaint(new GradientPaint(0, 0, a, ORIG_W, ORIG_H, b));
            g.fillRect(0, 0, ORIG_W, ORIG_H);
            g.setColor(new Color(255, 255, 255, 60));
            for (int i = 0; i < 8; i++) {
                int d = 120 + ((id + i * 37) % 7) * 60;
                g.fillOval((id * 53 + i * 211) % ORIG_W - d / 2, (id * 29 + i * 131) % ORIG_H - d / 2, d, d);
            }
            try {
                g.setColor(Color.WHITE);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 260));
                String text = "#" + id;
                int w = g.getFontMetrics().stringWidth(text);
                g.drawString(text, (ORIG_W - w) / 2, ORIG_H / 2 + 90);
                if (variant > 0) {
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 90));
                    g.drawString("v" + variant, 60, ORIG_H - 60);   // 교체한 이미지는 눈으로도 구분된다
                }
            } catch (RuntimeException | LinkageError | InternalError e) {
                // 폰트가 없는 최소 JDK 서버에서는 번호 글자 없이 그림만 만든다.
                if (!fontWarned) {
                    fontWarned = true;
                    log.warn("폰트를 쓸 수 없어 번호 글자를 생략합니다: {}", e.toString());
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
