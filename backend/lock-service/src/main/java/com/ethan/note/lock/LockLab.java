package com.ethan.note.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * 쇼핑몰 주문으로 락 전략을 비교하는 실험.
 * 트랜잭션을 프레임워크(@Transactional)에 맡기지 않고 JDBC 로 직접 다룬다. 락이 언제 잡히고 풀리는지가 실험의 대상이라서
 * 그 시점이 코드에 그대로 보이게 하려는 것이다. 격리 수준은 MariaDB 기본값(REPEATABLE READ)이다.
 */
@Service
public class LockLab {

    private static final Logger log = LoggerFactory.getLogger(LockLab.class);

    /** 동시에 주문하는 스레드 수의 한도(풀 크기 60 안에 든다). */
    static final int MAX_THREADS = 50;
    /** 낙관적 락이 버전 충돌로 다시 시도하는 최대 횟수. */
    static final int OPTIMISTIC_TRIES = 10;
    /** 데드락이 나면 처음부터 다시 시도하는 최대 횟수(처음 시도 포함). */
    static final int DEADLOCK_TRIES = 5;
    /**
     * 첫 상품을 잠근 뒤 다음 상품을 잠그기 전의 짧은 틈(ms). 실제 주문 처리에서는 가격 계산, 쿠폰 확인 같은 일이 그 사이에 끼는 것을 흉내 낸다.
     * 이 틈이 없으면 두 요청이 우연히 겹쳐야만 데드락이 나서 재현이 들쭉날쭉해진다.
     */
    static final int LOCK_GAP_MS = 10;
    /** 재고가 부족해질 일이 없게 하려는 실험(데드락, 트랜잭션 길이)의 재고. */
    static final int BIG_STOCK = 1000;
    /** 한 실험의 제한 시간(ms). 이 안에 모든 주문이 끝나야 한다. */
    static final long RUN_LIMIT_MS = 120_000;

    public record Params(String id, Integer buyers, Integer stock, Integer payMs) {
    }

    public record ProductState(int id, int initial, int sold, int finalStock) {
    }

    /**
     * @param ok        주문에 성공한 수
     * @param soldOut   재고가 없어 정상적으로 거절된 수
     * @param failed    오류로 실패한 수(데드락으로 밀려났거나, 락 대기 시간 초과, 재시도 초과 등)
     * @param orders    DB 에 실제로 남은 주문 수(ok 와 같아야 한다)
     * @param oversold  재고보다 더 팔린 수량
     * @param consistent 모든 상품에서 "처음 재고 - 팔린 수량 = 남은 재고"이고 재고가 음수가 아니며 주문 수가 맞으면 true
     */
    public record Result(String id, String group, String title, int buyers, int threads, int payMs, long elapsedMs, int ok, int soldOut,
                         int failed, int deadlocks, int timeouts, int retries, int orders, List<ProductState> products, int oversold,
                         boolean consistent, String firstError, String finishedAt, boolean saved) {
        public Result withSaved(boolean value) {
            return new Result(id, group, title, buyers, threads, payMs, elapsedMs, ok, soldOut, failed, deadlocks, timeouts, retries, orders,
                    products, oversold, consistent, firstError, finishedAt, value);
        }
    }

    private enum Outcome { OK, SOLD_OUT, FAILED, CONFLICT }

    private static final class Counters {
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger soldOut = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger deadlocks = new AtomicInteger();
        final AtomicInteger timeouts = new AtomicInteger();
        final AtomicInteger retries = new AtomicInteger();
        final AtomicReference<String> firstError = new AtomicReference<>();
    }

    private interface Work {
        Outcome run(Connection c) throws SQLException, InterruptedException;
    }

    /** 제한 시간을 넘겨 멈추려 했지만 아직 살아 있는 스레드(보통 비어 있다). */
    private volatile List<Thread> leftover = List.of();

    private final DataSource ds;
    private final LockEvents events;

    public LockLab(DataSource ds, LockEvents events) {
        this.ds = ds;
        this.events = events;
    }

    /** 성공은 5초, 실패는 1초 동안 재사용한다(DB 가 되살아나면 금방 풀리게). */
    private static final long CHECK_TTL_NANOS = 5_000_000_000L;
    private static final long CHECK_FAIL_TTL_NANOS = 1_000_000_000L;

    private record Checked(long at, String result) {
    }

    private volatile Checked checked;

    /**
     * DB 에 연결되고 실험 테이블이 있는지 본다. 문제가 있으면 메시지, 없으면 null.
     * 화면이 몇 초마다 부르므로 결과를 5초 동안 재사용한다(DB 가 죽었을 때 요청마다 연결을 기다리며 쌓이지 않게).
     */
    public String check() {
        Checked last = checked;
        if (fresh(last)) {
            return last.result();
        }
        synchronized (this) { // DB 가 죽어 있으면 연결을 기다리는 데 몇 초 걸린다. 그동안 들어온 요청은 하나가 끝나길 기다렸다가 그 결과를 쓴다.
            last = checked;
            if (fresh(last)) {
                return last.result();
            }
            String result = null;
            // 실험 테이블과, 결과를 저장하는 test_result 테이블(juso-service 의 스키마)이 모두 있어야 한다. 행을 세지 않고 있는지만 본다.
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                for (String table : new String[]{"lock_product", "test_result"}) {
                    st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1").close();
                }
            } catch (SQLException e) {
                log.warn("DB 확인 실패: {}", e.toString()); // 접속 주소 같은 자세한 내용은 서버 로그에만 남기고 화면에는 내보내지 않는다(DB 가 죽은 동안 반복되므로 스택은 싣지 않는다)
                result = "DB 에 연결하지 못했거나 lock_*, test_result 테이블이 없습니다. lock-service 로그를 확인하세요.";
            }
            checked = new Checked(System.nanoTime(), result);
            return result;
        }
    }

    /** 확인 결과를 아직 재사용해도 되는지. nanoTime 은 차이로만 비교한다(값 자체는 음수일 수도 있다). */
    private static boolean fresh(Checked c) {
        return c != null && System.nanoTime() - c.at() < (c.result() == null ? CHECK_TTL_NANOS : CHECK_FAIL_TTL_NANOS);
    }

    /** 요청 값 검사. 컨트롤러가 한 번만 부른다(run() 은 검사된 값만 받는다). */
    public static void validate(Params p) {
        if (p == null || p.id() == null) {
            throw new BadRequestException("실험 id 가 없습니다.");
        }
        Experiment ex = Experiment.of(p.id());
        if (p.buyers() != null && (p.buyers() < 2 || p.buyers() > 200)) {
            throw new BadRequestException("주문자 수는 2 ~ 200 이어야 합니다.");
        }
        // 그 실험에서 쓰지 않는 값(재고는 재고 차감 실험만, 결제 시간은 트랜잭션 길이 실험만)은 무시하므로 범위도 검사하지 않는다.
        if (ex.group().equals("stock") && p.stock() != null && (p.stock() < 1 || p.stock() > 1000)) {
            throw new BadRequestException("재고는 1 ~ 1000 이어야 합니다.");
        }
        if (ex.group().equals("tx") && p.payMs() != null && (p.payMs() < 0 || p.payMs() > 100)) {
            throw new BadRequestException("결제 시간은 0 ~ 100ms 이어야 합니다.");
        }
    }

    /** 실험 하나를 실행한다. 호출하는 쪽이 WorkGuard 로 한 번에 하나만 돌게 한다. */
    public Result run(Params p) {
        if (leftover.stream().anyMatch(Thread::isAlive)) {
            // 이전 실험에서 멈추지 않은 스레드가 아직 DB 를 쓰고 있으면 새 실험의 초기화와 섞여 결과가 틀어진다.
            throw new LockException("이전 실험의 스레드가 아직 끝나지 않았습니다. 잠시 뒤에 다시 실행하세요");
        }
        Experiment ex = Experiment.of(p.id());
        int buyers = p.buyers() != null ? p.buyers() : ex.defaultBuyers();
        int stock = ex.group().equals("stock") ? (p.stock() != null ? p.stock() : 10) : BIG_STOCK;
        int payMs = ex.group().equals("tx") ? (p.payMs() != null ? p.payMs() : 20) : 0;
        int threads = Math.min(buyers, MAX_THREADS);
        String title = ex.title();

        events.info("lock", title + " 시작: 주문 " + buyers + "건을 동시에 보냅니다");
        reset(stock);
        Counters k = new Counters();
        warmUp(threads);
        long elapsed = execute(buyers, threads, i -> attempt(ex, i, payMs, k));
        Result r = verify(ex, title, buyers, threads, payMs, stock, elapsed, k);
        events.success("lock", title + " 끝: 성공 " + r.ok() + ", 품절 " + r.soldOut() + ", 실패 " + r.failed()
                + ", 데드락 " + r.deadlocks() + (r.consistent() ? ", 재고 맞음" : ", 재고 어긋남") + " (" + r.elapsedMs() + "ms)");
        return r;
    }

    /** 주문자 한 명이 하는 일. 모든 스레드가 같이 출발하도록 execute() 가 맞춘다. */
    private void attempt(Experiment ex, int buyer, int payMs, Counters k) {
        Outcome o;
        try {
            o = switch (ex) {
                case STOCK_NONE -> tx(c -> stockNone(c, buyer));
                case STOCK_ATOMIC -> tx(c -> stockAtomic(c, buyer));
                case STOCK_PESSIMISTIC -> tx(c -> stockPessimistic(c, buyer));
                case STOCK_OPTIMISTIC -> stockOptimistic(buyer, k);
                case DEADLOCK_CROSS -> tx(c -> lockTwo(c, buyer, buyer % 2 == 0 ? new int[]{1, 2} : new int[]{2, 1}));
                case DEADLOCK_ORDERED -> tx(c -> lockTwo(c, buyer, new int[]{1, 2}));
                case DEADLOCK_RETRY -> deadlockRetry(buyer, k);
                case TX_LONG -> tx(c -> payInside(c, buyer, payMs));
                case TX_SHORT -> payOutside(buyer, payMs);
            };
        } catch (SQLException e) {
            o = classify(e, k);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            k.firstError.compareAndSet(null, "실험이 중단되었습니다");
            o = Outcome.FAILED;
        } catch (RuntimeException e) {
            // 예상 못 한 오류로 스레드가 조용히 끝나면 그 주문들이 실행도 집계도 되지 않는다. 실패로 세고 이유를 남긴다.
            log.warn("주문 처리 중 예상하지 못한 오류", e);
            k.firstError.compareAndSet(null, "예상하지 못한 오류: " + e.getClass().getSimpleName());
            o = Outcome.FAILED;
        }
        switch (o) {
            case OK -> k.ok.incrementAndGet();
            case SOLD_OUT -> k.soldOut.incrementAndGet();
            default -> k.failed.incrementAndGet(); // CONFLICT 가 여기까지 오는 일은 없다(낙관적 락이 재시도하거나 FAILED 로 바꾼다)
        }
    }

    // ── 재고 차감 4가지 ────────────────────────────────────────────

    /** 락 없이: 재고를 읽고, 계산하고, 그 값을 쓴다. 읽은 뒤 다른 주문이 재고를 바꿔도 모른다(갱신 손실). */
    private Outcome stockNone(Connection c, int buyer) throws SQLException {
        int stock = readStock(c, 1, false);
        if (stock < 1) {
            return Outcome.SOLD_OUT;
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE lock_product SET stock = ? WHERE id = 1")) {
            ps.setInt(1, stock - 1); // 읽은 값에서 계산한 값을 그대로 덮어쓴다
            ps.executeUpdate();
        }
        order(c, buyer, 1);
        return Outcome.OK;
    }

    /** 원자적 UPDATE: 확인과 차감을 SQL 한 문장에 넣는다. 행 락은 이 한 문장 동안만 잡힌다. */
    private Outcome stockAtomic(Connection c, int buyer) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE lock_product SET stock = stock - 1 WHERE id = 1 AND stock >= 1")) {
            if (ps.executeUpdate() == 0) {
                return Outcome.SOLD_OUT;
            }
        }
        order(c, buyer, 1);
        return Outcome.OK;
    }

    /** 비관적 락: 읽을 때부터 행을 잠가서 다른 주문이 끝날 때까지 기다리게 한다. */
    private Outcome stockPessimistic(Connection c, int buyer) throws SQLException {
        int stock = readStock(c, 1, true);
        if (stock < 1) {
            return Outcome.SOLD_OUT;
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE lock_product SET stock = stock - 1 WHERE id = 1")) {
            ps.executeUpdate();
        }
        order(c, buyer, 1);
        return Outcome.OK;
    }

    /** 낙관적 락: 잠그지 않고 읽은 뒤, 쓸 때 "그 사이 아무도 안 바꿨나(버전)"를 확인한다. 바뀌었으면 처음부터 다시 한다. */
    private Outcome stockOptimistic(int buyer, Counters k) throws SQLException, InterruptedException {
        for (int attempt = 1; attempt <= OPTIMISTIC_TRIES; attempt++) {
            Outcome o = tx(c -> {
                int stock;
                int version;
                try (PreparedStatement ps = c.prepareStatement("SELECT stock, version FROM lock_product WHERE id = 1");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    stock = rs.getInt(1);
                    version = rs.getInt(2);
                }
                if (stock < 1) {
                    return Outcome.SOLD_OUT;
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE lock_product SET stock = stock - 1, version = version + 1 WHERE id = 1 AND version = ?")) {
                    ps.setInt(1, version);
                    if (ps.executeUpdate() == 0) {
                        return Outcome.CONFLICT; // 그 사이 다른 주문이 먼저 바꿨다
                    }
                }
                order(c, buyer, 1);
                return Outcome.OK;
            });
            if (o != Outcome.CONFLICT) {
                return o;
            }
            if (attempt < OPTIMISTIC_TRIES) {
                k.retries.incrementAndGet();
                Thread.sleep(ThreadLocalRandom.current().nextInt(1, 8)); // 모두 같은 순간에 다시 부딪히지 않게 흩는다
            }
        }
        k.firstError.compareAndSet(null, "버전 충돌로 " + OPTIMISTIC_TRIES + "번 다시 시도했지만 실패했습니다");
        return Outcome.FAILED;
    }

    // ── 데드락: 여러 상품을 잠그는 순서 ───────────────────────────────

    /** 상품 두 개를 정해진 순서로 잠그고 둘 다 1개씩 차감한다(장바구니에 두 상품이 든 주문). */
    private Outcome lockTwo(Connection c, int buyer, int[] order) throws SQLException, InterruptedException {
        for (int i = 0; i < order.length; i++) {
            readStock(c, order[i], true);
            if (i == 0) {
                Thread.sleep(LOCK_GAP_MS);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE lock_product SET stock = stock - 1 WHERE id = ?")) {
            for (int id : order) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
        order(c, buyer, 1, 2);
        return Outcome.OK;
    }

    /** 잠그는 순서는 그대로 두고, 데드락으로 밀려나면 트랜잭션을 처음부터 다시 한다(대기 시간을 흩어서). */
    private Outcome deadlockRetry(int buyer, Counters k) throws SQLException, InterruptedException {
        int[] order = buyer % 2 == 0 ? new int[]{1, 2} : new int[]{2, 1};
        for (int attempt = 1; ; attempt++) {
            try {
                return tx(c -> lockTwo(c, buyer, order));
            } catch (SQLException e) {
                if (!isDeadlock(e)) {
                    throw e;
                }
                k.deadlocks.incrementAndGet();
                if (attempt >= DEADLOCK_TRIES) {
                    k.firstError.compareAndSet(null, "데드락으로 " + DEADLOCK_TRIES + "번 다시 시도했지만 실패했습니다");
                    return Outcome.FAILED;
                }
                k.retries.incrementAndGet();
                Thread.sleep(ThreadLocalRandom.current().nextInt(5, 30));
            }
        }
    }

    // ── 트랜잭션 길이: 결제 호출 위치 ─────────────────────────────────

    /** 결제를 트랜잭션 안에서: 행 락을 잡은 채로 결제를 기다리므로 다른 주문이 줄을 선다. */
    private Outcome payInside(Connection c, int buyer, int payMs) throws SQLException, InterruptedException {
        readStock(c, 1, true);
        Thread.sleep(payMs); // 외부 결제 서버를 부르는 시간을 흉내 낸다
        try (PreparedStatement ps = c.prepareStatement("UPDATE lock_product SET stock = stock - 1 WHERE id = 1")) {
            ps.executeUpdate();
        }
        order(c, buyer, 1);
        return Outcome.OK;
    }

    /** 결제를 트랜잭션 밖에서: 결제는 동시에 진행하고, 재고 차감만 짧은 트랜잭션으로 한다. */
    private Outcome payOutside(int buyer, int payMs) throws SQLException, InterruptedException {
        Thread.sleep(payMs);
        return tx(c -> stockAtomic(c, buyer));
    }

    // ── 공통 ──────────────────────────────────────────────────────

    /** 연결 하나로 트랜잭션을 실행한다. OK 가 아니면 되돌린다. 예외가 나도 반드시 되돌린다. */
    private Outcome tx(Work w) throws SQLException, InterruptedException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                Outcome o = w.run(c);
                if (o == Outcome.OK) {
                    c.commit();
                } else {
                    c.rollback();
                }
                return o;
            } catch (SQLException | InterruptedException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignore) {
                    // 원래 예외를 우선한다
                }
                throw e;
            }
        }
    }

    private static int readStock(Connection c, int productId, boolean forUpdate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT stock FROM lock_product WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void order(Connection c, int buyer, int... productIds) throws SQLException {
        long orderId;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO lock_order (buyer) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, buyer);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                orderId = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO lock_order_item (order_id, product_id, qty) VALUES (?, ?, 1)")) {
            for (int id : productIds) {
                ps.setLong(1, orderId);
                ps.setInt(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** 예외와 그 원인들 중 조건에 맞는 SQLException 이 있는지. */
    private static boolean anySql(SQLException e, java.util.function.Predicate<SQLException> test) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && test.test(s)) {
                return true;
            }
        }
        return false;
    }

    /** 데드락: MariaDB 오류 1213(SQLState 40001). */
    private static boolean isDeadlock(SQLException e) {
        return anySql(e, s -> s.getErrorCode() == 1213 || "40001".equals(s.getSQLState()));
    }

    /** 락 대기 시간 초과: MariaDB 오류 1205. */
    private static boolean isTimeout(SQLException e) {
        return anySql(e, s -> s.getErrorCode() == 1205);
    }

    private Outcome classify(SQLException e, Counters k) {
        if (isDeadlock(e)) {
            k.deadlocks.incrementAndGet();
            k.firstError.compareAndSet(null, "데드락이 나서 DB 가 이 주문을 취소했습니다(오류 1213)");
        } else if (isTimeout(e)) {
            k.timeouts.incrementAndGet();
            k.firstError.compareAndSet(null, "락을 기다리다 시간이 지났습니다(오류 1205, 대기 한도 5초)");
        } else {
            log.warn("주문 처리 중 DB 오류", e);
            k.firstError.compareAndSet(null, "DB 오류로 실패했습니다(lock-service 로그 확인)"); // 드라이버 메시지에는 접속 주소가 있을 수 있어 화면에는 싣지 않는다
        }
        return Outcome.FAILED;
    }

    /** 이전 실험의 데이터를 지우고 처음 상태로 되돌린다. 상품 1, 2 만 둔다. */
    private void reset(int stock) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM lock_order_item");
                st.executeUpdate("DELETE FROM lock_order");
                st.executeUpdate("DELETE FROM lock_product");
                st.executeUpdate("INSERT INTO lock_product (id, name, stock, version) VALUES (1, '한정판 운동화', " + stock + ", 0), (2, '양말', " + stock + ", 0)");
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new LockException("실험 데이터를 초기화하지 못했습니다(DB 연결 확인)", e);
        }
    }

    /**
     * 연결 풀을 미리 데운다. 풀은 평소에 연결을 2개만 들고 있어서, 데우지 않으면 스레드 수만큼 연결을 만드는 시간이 측정에 들어가고
     * 스레드가 한꺼번에 부딪히지 못하고 어긋나서 도착한다.
     */
    private void warmUp(int threads) {
        List<Connection> opened = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                opened.add(ds.getConnection());
            }
        } catch (SQLException e) {
            throw new LockException("DB 연결을 충분히 열지 못했습니다(DB 와 연결 설정 확인)", e);
        } finally {
            for (Connection c : opened) {
                try {
                    c.close();
                } catch (SQLException ignore) {
                    // 풀로 돌려주는 것이라 실패해도 영향이 없다
                }
            }
        }
    }

    /** 스레드를 만들어 대기시킨 뒤 한꺼번에 출발시키고, 모두 끝날 때까지 걸린 시간(ms)을 돌려준다. */
    private long execute(int buyers, int threads, java.util.function.IntConsumer buyer) {
        AtomicInteger next = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = next.getAndIncrement(); i < buyers && !Thread.currentThread().isInterrupted(); i = next.getAndIncrement()) {
                    buyer.accept(i);
                }
            }, "lock-buyer-" + t);
            th.setDaemon(true);
            workers.add(th);
            th.start();
        }
        try {
            ready.await();
            long t0 = System.nanoTime();
            start.countDown();
            long deadline = System.nanoTime() + RUN_LIMIT_MS * 1_000_000L;
            for (Thread th : workers) {
                th.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
            }
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            if (workers.stream().anyMatch(Thread::isAlive)) {
                // 제한 시간 안에 끝나지 않았다. 남은 스레드가 다음 실험의 데이터를 건드리지 않도록 멈추고 기다린 뒤 실패로 알린다.
                workers.forEach(Thread::interrupt);
                long stop = System.nanoTime() + 5_000_000_000L; // 멈추기를 기다리는 시간은 전체로 5초
                for (Thread th : workers) {
                    th.join(Math.max(1, (stop - System.nanoTime()) / 1_000_000));
                }
                leftover = workers.stream().filter(Thread::isAlive).toList(); // DB 응답을 기다리는 중이라 인터럽트로 안 멈춘 스레드가 있을 수 있다
                throw new LockException("실험이 제한 시간(" + RUN_LIMIT_MS / 1000 + "초) 안에 끝나지 않아 멈췄습니다");
            }
            return elapsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockException("실험이 중단되었습니다");
        }
    }

    /** 끝난 뒤 DB 에 실제로 남은 값으로 재고가 맞는지 확인한다(카운터가 아니라 DB 를 믿는다). */
    private Result verify(Experiment ex, String title, int buyers, int threads, int payMs, int initial, long elapsed, Counters k) {
        List<ProductState> states = new ArrayList<>();
        int orders;
        try (Connection c = ds.getConnection()) {
            for (int id : ex.products()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT p.stock, (SELECT COALESCE(SUM(qty), 0) FROM lock_order_item WHERE product_id = p.id) FROM lock_product p WHERE p.id = ?")) {
                    ps.setInt(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        states.add(new ProductState(id, initial, rs.getInt(2), rs.getInt(1)));
                    }
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lock_order")) {
                rs.next();
                orders = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new LockException("실험은 끝났지만 결과를 DB 에서 확인하지 못했습니다", e);
        }
        int oversold = 0;
        boolean consistent = orders == k.ok.get();
        for (ProductState s : states) {
            oversold += Math.max(0, s.sold() - s.initial());
            consistent &= s.finalStock() >= 0 && s.finalStock() == s.initial() - s.sold();
        }
        return new Result(ex.id(), ex.group(), title, buyers, threads, payMs, elapsed, k.ok.get(), k.soldOut.get(), k.failed.get(),
                k.deadlocks.get(), k.timeouts.get(), k.retries.get(), orders, states, oversold, consistent, k.firstError.get(),
                LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), true);
    }
}
