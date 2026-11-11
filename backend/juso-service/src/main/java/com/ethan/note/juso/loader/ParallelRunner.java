package com.ethan.note.juso.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 생산자 1개(파일 읽기·파싱) + 워커 N개. 워커가 청크로 무엇을 하는지는 호출자가 정한다.
 * 파일 순서가 섞여 들어가므로 InnoDB에서는 PK 순서 삽입 이점이 줄어든다(비교해 볼 포인트).
 */
public final class ParallelRunner {
    private static final Logger log = LoggerFactory.getLogger(ParallelRunner.class);


    private static final List<String[]> POISON = new ArrayList<>();   // 참조 비교용 종료 신호

    /** 워커 스레드 하나의 일. 자기 커넥션/세션을 열고 {@code source.next()}가 null 일 때까지 청크를 처리한다. */
    @FunctionalInterface
    public interface Worker {
        void run(ChunkSource source) throws Exception;
    }

    public interface ChunkSource {
        /** 다음 청크. 더 없으면 null. */
        List<String[]> next() throws InterruptedException;
    }

    private ParallelRunner() {
    }

    public static void run(LoadContext ctx, Worker worker) throws Exception {
        int threads = ctx.threads();
        BlockingQueue<List<String[]>> queue = new ArrayBlockingQueue<>(threads * 2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> workers = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                workers.add(pool.submit(() -> {
                    try {
                        worker.run(() -> {
                            List<String[]> chunk = queue.take();
                            return chunk == POISON ? null : chunk;
                        });
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    }
                    return null;
                }));
            }

            try {
                Rows.forEachChunk(ctx.file(), ctx.batchSize(), chunk -> {
                    if (error.get() != null) {   // 워커 하나가 죽으면 바로 중단
                        throw new IllegalStateException("워커 오류로 중단", error.get());
                    }
                    put(queue, chunk, error);
                });
                for (int i = 0; i < threads; i++) {
                    put(queue, POISON, error);
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }

            for (Future<?> f : workers) {
                if (error.get() != null) {
                    f.cancel(true);
                } else {
                    f.get();
                }
            }
        } finally {
            pool.shutdownNow();
            // 실행 중인 JDBC 호출이 끝나기 전에 호출자가 정리 작업(ALTER, 파일 삭제)에 들어가지 않게 기다린다.
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("워커가 60초 안에 끝나지 않았습니다.");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        Throwable t = error.get();
        if (t != null) {
            throw t instanceof Exception e ? e : new RuntimeException(t);
        }
    }

    /** 워커가 죽어 큐가 안 비워지면 영원히 막히지 않도록, 주기적으로 오류 여부를 확인하며 넣는다. */
    private static void put(BlockingQueue<List<String[]>> queue, List<String[]> chunk,
                            AtomicReference<Throwable> error) throws InterruptedException {
        while (!queue.offer(chunk, 500, TimeUnit.MILLISECONDS)) {
            if (error.get() != null) {
                throw new IllegalStateException("워커 오류로 중단", error.get());
            }
        }
    }
}
