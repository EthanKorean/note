package com.ethan.note.catchmind;

import com.ethan.note.catchmind.Messages.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import java.text.Normalizer;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 하나뿐인 방과 게임 진행. 모든 상태 변경은 이 객체의 락(synchronized) 안에서만 일어난다.
 * 단계: LOBBY(모두 Ready 면 자동 시작) → DRAWING(출제자가 그림, 나머지가 맞힘) → REVEAL(정답 공개) → … → RESULT.
 * 플레이어의 정체는 접속 token 이고, 화면에는 token 대신 공개용 번호(pid)만 내보낸다.
 */
@Component
public class GameRoom {
    private static final Logger log = LoggerFactory.getLogger(GameRoom.class);

    static final int MAX_PLAYERS = 8;
    static final int SCORE = 50;
    static final int GRACE_SEC = 10;   // 연결이 끊긴 사람을 기다려 주는 시간(새로고침 대비)
    static final int REVEAL_SEC = 4;
    static final int MAX_STROKE_CHUNKS = 2000;  // 조각 수 × 메시지 껍데기 + 점 수 × 점 크기가 스냅샷 한도(512KB) 아래가 되게
    static final int MAX_POINTS = 200;
    static final int MAX_TOTAL_POINTS = 12000;   // 늦게 들어온 사람에게 보낼 스냅샷이 웹소켓 전송 버퍼(512KB)를 넘지 않게 한다
    static final long HELLO_GAP_MS = 500;
    static final long GUESS_GAP_MS = 300;        // 채팅·정답 도배 방지
    static final int PALETTE = 8;      // 색 번호 0..7 (실제 색은 화면의 CSS 토큰). 화면의 stores/catch.ts PALETTE 와 같아야 한다

    // 매 메시지마다 정규식을 다시 컴파일하지 않도록 한 번만 만든다.
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern INVISIBLE_NICK = Pattern.compile("[\\p{Cc}\\p{Cf}\\u115F\\u1160\\u3164\\u2800]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    enum Phase { LOBBY, DRAWING, REVEAL, RESULT }

    static final class Player {
        final String token;
        final int pid;
        final String nick;
        boolean ready, left, spectator, solved;
        int score;
        long lastChat;
        final Set<String> sessions = new HashSet<>();
        ScheduledFuture<?> grace;
        int graceGen;        // 유예 타이머 세대(이미 락을 기다리던 오래된 작업이 새 유예를 끊지 못하게)

        Player(String token, int pid, String nick) {
            this.token = token;
            this.pid = pid;
            this.nick = nick;
        }
    }

    private final SimpMessagingTemplate out;
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "catch-timer");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Player> players = new LinkedHashMap<>(); // 입장 순서 유지
    private final List<Draw> strokes = new ArrayList<>();
    private final Deque<String> queue = new ArrayDeque<>();            // 앞으로 그릴 사람(token)
    private final List<String> wordPool = new ArrayList<>();

    private Phase phase = Phase.LOBBY;
    private int laps = 1, seconds = 60;
    private int nextPid = 1, total, round;
    private String hostToken;
    private Player drawer, solver;
    private String word, revealWord;
    private long endsAt;
    private long serial;                 // 단계가 바뀔 때마다 증가 → 오래된 타이머를 무시한다
    private final Map<String, Long> lastHello = new LinkedHashMap<>(16, 0.75f, true); // 접근 순서: 오래 안 쓴 항목부터 밀려난다
    private final Set<String> closedSessions = new LinkedHashSet<>(); // 이미 끊긴 세션(늦게 도착한 join 이 유령 세션을 남기지 않게)
    private long stateSeq;               // 상태 메시지 번호(클라이언트가 순서가 뒤바뀐 오래된 상태를 무시한다)
    private boolean waitNotified;        // 연결을 기다리는 안내를 한 번만
    private boolean capNotified;         // 그림 용량 한도 안내를 라운드당 한 번만
    private int totalPoints;             // 이번 라운드에 받은 점 수
    private ScheduledFuture<?> timer;

    public GameRoom(SimpMessagingTemplate out) {
        this.out = out;
    }

    @PreDestroy
    void shutdown() {
        sched.shutdownNow();
    }

    // ================= 입장·퇴장 =================

    public synchronized void join(String token, String sessionId, String rawNick) {
        if (closedSessions.contains(sessionId)) return; // 연결이 이미 끊긴 뒤에 도착한 요청
        Player p = players.get(token);
        if (p != null && !p.left) { // 새로고침 등으로 같은 사람이 다시 접속
            if (p.sessions.contains(sessionId) && tooFast(token)) return; // 같은 연결의 반복 요청은 스냅샷을 다시 보내지 않는다
            p.sessions.add(sessionId);
            cancelGrace(p);
            tryAutoStart(); // 마지막으로 Ready 를 누른 사람이 새로고침으로 돌아오면 시작 조건이 이제야 채워질 수 있다
        } else {
            // NFKC 로 전각·호환 문자를 일반 문자로 맞추고, 눈에 안 보이는 문자(제로폭 공백, 한글 채움 문자, 점자 빈칸 등)를
            // 뺀다: 빈 이름이나 같은 이름처럼 보이는 두 사람을 막는다.
            String nick = rawNick == null ? ""
                    : INVISIBLE_NICK.matcher(Normalizer.normalize(rawNick, Normalizer.Form.NFKC)).replaceAll("").trim();
            if (nick.isEmpty() || nick.length() > 8) {
                toUser(token, "/queue/me", new Me(null, "닉네임은 1~8자로 입력하세요.", null, stateSeq + 1));
                return;
            }
            if (players.values().stream().anyMatch(o -> !o.left && o.nick.equalsIgnoreCase(nick))) {
                toUser(token, "/queue/me", new Me(null, "이미 쓰고 있는 닉네임입니다.", null, stateSeq + 1));
                return;
            }
            if (live().size() >= MAX_PLAYERS) {
                toUser(token, "/queue/me", new Me(null, "방이 가득 찼습니다. (최대 " + MAX_PLAYERS + "명)", null, stateSeq + 1));
                return;
            }
            if (p != null) players.remove(token); // 나갔던 사람이 다시 들어오면 새 참가자로 본다(검증을 통과한 뒤에만 지운다)
            queue.removeIf(token::equals);        // 남아 있던 출제 순서도 지워, 관전자가 출제자로 뽑히지 않게 한다
            p = new Player(token, nextPid++, nick);
            p.sessions.add(sessionId);
            p.spectator = phase == Phase.DRAWING || phase == Phase.REVEAL; // 진행 중이면 다음 게임부터
            players.put(token, p);
            if (hostToken == null || !isLive(hostToken)) hostToken = pickHost();
            system(nick + "님이 입장했습니다." + (p.spectator ? " (관전 중 · 다음 게임부터 참여)" : ""));
        }
        toUser(token, "/queue/me", new Me(p.pid, null, p == drawer && phase == Phase.DRAWING ? word : null, stateSeq + 1));
        toUser(token, "/queue/strokes", new Strokes(List.copyOf(strokes)));
        broadcastState();
    }

    /** 큰 스냅샷을 보내는 요청(hello, 이미 들어온 사람의 join)을 같은 사람이 연타하지 못하게 한다. true 면 너무 빠르다. */
    private boolean tooFast(String token) {
        long now = System.currentTimeMillis();
        Long last = lastHello.put(token, now);
        if (lastHello.size() > 500) lastHello.remove(lastHello.keySet().iterator().next());
        return last != null && now - last < HELLO_GAP_MS;
    }

    /** 아직 입장하지 않은 사람에게 지금 방 상태와 그림을 보내 준다(상태는 변화가 있을 때만 방송되므로). */
    public synchronized void hello(String token) {
        if (tooFast(token)) return;
        toUser(token, "/queue/state", stateOf());
        toUser(token, "/queue/strokes", new Strokes(List.copyOf(strokes)));
    }

    public synchronized void leave(String token) {
        Player p = players.get(token);
        if (p != null && !p.left) remove(p, "나갔습니다.");
    }

    /** 웹소켓이 끊겼다. 곧 복귀할 수 있으니 GRACE_SEC 동안 기다렸다가 퇴장 처리한다. */
    public synchronized void disconnected(String sessionId) {
        closedSessions.add(sessionId);
        if (closedSessions.size() > 500) closedSessions.remove(closedSessions.iterator().next());
        for (Player p : players.values()) {
            if (p.left || !p.sessions.remove(sessionId)) continue;
            if (p.sessions.isEmpty()) {
                cancelGrace(p);
                String token = p.token;
                int gen = ++p.graceGen;
                p.grace = sched.schedule(() -> expire(token, gen), GRACE_SEC, TimeUnit.SECONDS);
                broadcastState(); // 오프라인 표시
            }
            return;
        }
    }

    private synchronized void expire(String token, int gen) {
        try {
            Player p = players.get(token);
            if (p != null && !p.left && p.graceGen == gen && p.sessions.isEmpty()) remove(p, "연결이 끊겨 퇴장했습니다.");
        } catch (RuntimeException e) {
            log.error("expire failed", e);
        }
    }

    private void remove(Player p, String why) {
        cancelGrace(p);
        p.sessions.clear();
        boolean wasDrawer = p == drawer;
        if (phase == Phase.LOBBY) players.remove(p.token); // 게임 기록이 없으니 완전히 뺀다
        else {
            p.left = true;                                  // 게임 중·결과에는 "나감"으로 남긴다
            // 들어왔다 나가기를 반복해도 상태 메시지가 끝없이 커지지 않게, 나간 기록은 최근 MAX_PLAYERS 개만 둔다.
            List<String> gone = players.values().stream().filter(o -> o.left && o != drawer && o != solver).map(o -> o.token).toList();
            for (int i = 0; i < gone.size() - MAX_PLAYERS; i++) players.remove(gone.get(i));
        }
        system(p.nick + "님이 " + why);
        if (p.token.equals(hostToken)) {
            hostToken = pickHost();
        }
        if (live().isEmpty()) { resetRoom(); return; }

        switch (phase) {
            case DRAWING -> {
                if (wasDrawer) { system("출제자가 나가 이 라운드는 점수 없이 넘어갑니다."); endRound(null); return; }
                if (participants() < 2) { finishGame(); return; }
            }
            case REVEAL -> { if (participants() < 2) { finishGame(); return; } }
            default -> { }
        }
        broadcastState();
        tryAutoStart();
    }

    // ================= 로비 =================

    public synchronized void ready(String token, boolean ready) {
        Player p = livePlayer(token);
        if (p == null || (phase != Phase.LOBBY && phase != Phase.RESULT)) return;
        p.ready = ready;
        broadcastState();
        tryAutoStart();
    }

    /** 방장만 바꿀 수 있다. 화면이 막아도 서버에서 한 번 더 확인한다. */
    public synchronized void options(String token, int laps, int seconds) {
        if (!token.equals(hostToken) || (phase != Phase.LOBBY && phase != Phase.RESULT)) {
            toUser(token, "/queue/state", stateOf()); // 거절했다: 요청한 사람의 화면을 실제 설정으로 되돌리도록 현재 상태를 보낸다
            return;
        }
        int newLaps = Math.max(1, Math.min(3, laps));
        int newSeconds = Math.max(30, Math.min(120, seconds));
        if (newLaps != this.laps || newSeconds != this.seconds) {
            this.laps = newLaps;
            this.seconds = newSeconds;
            for (Player p : players.values()) p.ready = false; // 규칙이 실제로 바뀌었으니 다시 확인받는다
        }
        broadcastState();
    }

    private void tryAutoStart() {
        if (phase != Phase.LOBBY && phase != Phase.RESULT) return;
        List<Player> l = live();
        // 연결이 끊긴 사람(유예 중)이 Ready 만 눌러 두고 사라졌을 때 시작되지 않게, 모두 접속 중이어야 한다.
        if (l.size() >= 2 && l.stream().allMatch(p -> p.ready && !p.sessions.isEmpty())) startGame();
    }

    private void startGame() {
        players.values().removeIf(p -> p.left);
        for (Player p : players.values()) {
            p.score = 0;
            p.ready = false;
            p.spectator = false;
            p.solved = false;
        }
        queue.clear();
        for (int i = 0; i < laps; i++) for (Player p : players.values()) queue.add(p.token);
        total = queue.size();
        round = 0;
        wordPool.clear();
        wordPool.addAll(Words.ALL);
        Collections.shuffle(wordPool);
        system("게임을 시작합니다! " + laps + "바퀴 · 라운드당 " + seconds + "초");
        nextRound();
    }

    // ================= 라운드 진행 =================

    private void nextRound() {
        cancelTimer();
        Player next = null;
        while (next == null) {
            String t = queue.poll();
            if (t == null) { finishGame(); return; }
            Player c = players.get(t);
            if (c == null || c.left || c.spectator) continue; // 나갔거나 관전이면 차례를 건너뛴다
            if (c.sessions.isEmpty() && phase != Phase.REVEAL) continue; // 기다릴 수 있는 건 라운드 사이(REVEAL)뿐이다
            if (c.sessions.isEmpty()) {
                // 새로고침·일시 끊김(유예 중)이면 이 사람 차례를 그대로 두고 잠시 뒤 다시 시도한다.
                // 유예가 끝나도 안 돌아오면 퇴장 처리되어 다음 시도에서 건너뛴다.
                queue.addFirst(t);
                if (!waitNotified) { waitNotified = true; system(c.nick + "님의 연결을 기다리는 중입니다… (최대 " + GRACE_SEC + "초)"); }
                long s = serial; // 지금은 REVEAL 단계: 같은 단계의 타이머로 다시 부른다
                timer = sched.schedule(() -> afterReveal(s), 1, TimeUnit.SECONDS);
                return;
            }
            next = c;
            waitNotified = false;
        }
        if (participants() < 2) { finishGame(); return; }

        drawer = next;
        solver = null;
        revealWord = null;
        word = wordPool.remove(wordPool.size() - 1); // 게임마다 목록 전체를 다시 채우고 라운드는 최대 24 이므로 비지 않는다
        for (Player p : players.values()) p.solved = false;
        strokes.clear();
        totalPoints = 0;
        capNotified = false;
        round++;
        phase = Phase.DRAWING;
        long s = ++serial;
        endsAt = System.currentTimeMillis() + seconds * 1000L;
        timer = sched.schedule(() -> timeUp(s), seconds, TimeUnit.SECONDS);

        out.convertAndSend("/topic/catch/draw", new Draw("clear", 0, 0, 0, null));
        toUser(drawer.token, "/queue/me", new Me(drawer.pid, null, word, stateSeq + 1));
        system(round + "라운드 · 그리는 사람: " + drawer.nick + " (" + word.length() + "글자)");
        broadcastState();
    }

    private synchronized void timeUp(long s) {
        try {
            if (s == serial && phase == Phase.DRAWING) {
                system("시간 종료! 아무도 맞히지 못했습니다.");
                endRound(null);
            }
        } catch (RuntimeException e) {
            log.error("timeUp failed", e);
        }
    }

    /** 라운드를 끝내고 정답을 공개한다. winner 가 null 이면 점수 없음. */
    private void endRound(Player winner) {
        cancelTimer();
        if (winner != null) {
            winner.score += SCORE;
            winner.solved = true;
        }
        solver = winner;
        revealWord = word;
        phase = Phase.REVEAL;
        long s = ++serial;
        timer = sched.schedule(() -> afterReveal(s), REVEAL_SEC, TimeUnit.SECONDS);
        if (winner != null) {
            out.convertAndSend("/topic/catch/chat", new Chat("correct", winner.pid, winner.nick,
                    winner.nick + "님이 정답을 맞혔습니다! +" + SCORE + "점 (정답: " + word + ")"));
        }
        broadcastState();
    }

    private synchronized void afterReveal(long s) {
        try {
            if (s == serial && phase == Phase.REVEAL) nextRound();
        } catch (RuntimeException e) {
            log.error("afterReveal failed", e);
        }
    }

    private void finishGame() {
        cancelTimer();
        waitNotified = false;
        serial++;
        phase = Phase.RESULT;
        drawer = null;
        solver = null;
        word = null;
        revealWord = null;
        for (Player p : players.values()) p.ready = false;
        system("게임이 끝났습니다. 모두 Ready 를 누르면 새 게임이 시작됩니다.");
        broadcastState();
    }

    private void resetRoom() {
        cancelTimer();
        serial++;
        players.clear();
        strokes.clear();
        totalPoints = 0;
        capNotified = false;
        waitNotified = false;
        laps = 1;
        seconds = 60;
        round = 0;
        total = 0;
        out.convertAndSend("/topic/catch/draw", new Draw("clear", 0, 0, 0, null));
        queue.clear();
        phase = Phase.LOBBY;
        drawer = null;
        solver = null;
        word = null;
        revealWord = null;
        hostToken = null;
        broadcastState();
    }

    // ================= 그림·정답 =================

    public synchronized void stroke(String token, StrokeReq r) {
        Player p = livePlayer(token);
        if (p == null || p != drawer || phase != Phase.DRAWING || r == null || r.pts() == null) return;
        if (r.c() < -1 || r.c() >= PALETTE || r.pts().length == 0 || r.pts().length > MAX_POINTS) return;
        if (capNotified) return; // 한도에 닿은 뒤에는 작은 조각도 받지 않는다(선 중간에 구멍이 생기지 않게). 지우기·새 라운드에서 풀린다.
        if (strokes.size() >= MAX_STROKE_CHUNKS || totalPoints + r.pts().length > MAX_TOTAL_POINTS) {
            capNotified = true;
            system("그림이 너무 길어 이후의 선은 다른 사람에게 보이지 않습니다. 지우고 다시 그려 보세요.");
            return;
        }
        double[][] pts = new double[r.pts().length][];
        for (int i = 0; i < pts.length; i++) {
            double[] q = r.pts()[i];
            if (q == null || q.length != 2) return;
            pts[i] = new double[]{round4(clamp01(q[0])), round4(clamp01(q[1]))};
        }
        Draw d = new Draw("stroke", r.sid(), r.c(), Math.max(1, Math.min(40, r.size())), pts);
        strokes.add(d);
        totalPoints += pts.length;
        out.convertAndSend("/topic/catch/draw", d);
    }

    public synchronized void clear(String token) {
        Player p = livePlayer(token);
        if (p == null || p != drawer || phase != Phase.DRAWING) return;
        strokes.clear();
        totalPoints = 0;
        capNotified = false;
        out.convertAndSend("/topic/catch/draw", new Draw("clear", 0, 0, 0, null));
    }

    /** 채팅이자 정답 입력. 맞으면 채팅에 올리지 않고 라운드를 끝낸다. */
    public synchronized void guess(String token, String raw) {
        Player p = livePlayer(token);
        // 보이지 않는 문자·방향 제어 문자·줄바꿈을 뺀다(채팅 위조, 정답 비교 방해 방지).
        String text = raw == null ? "" : INVISIBLE.matcher(raw).replaceAll("").trim();
        if (p == null || text.isEmpty() || text.length() > 50) return;
        if (phase == Phase.DRAWING) {
            if (p == drawer || p.spectator) return; // 출제자·관전자는 말할 수 없다(힌트·정답 노출 방지, 화면도 입력을 막아 둔다)
            if (norm(text).equals(norm(word))) { endRound(p); return; } // 정답은 도배 제한에 걸리지 않는다
        }
        long now = System.currentTimeMillis();
        if (now - p.lastChat < GUESS_GAP_MS) return;
        p.lastChat = now;
        out.convertAndSend("/topic/catch/chat", new Chat("chat", p.pid, p.nick, text));
    }

    // ================= 도우미 =================

    private static String norm(String s) {
        // NFKC: 자모가 풀려서 들어오는 한글(NFD)이나 전각 문자도 같은 글자로 본다.
        return WHITESPACE.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 소수 넷째 자리까지만 둔다(스냅샷 메시지 크기를 줄인다). */
    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    private List<Player> live() {
        return players.values().stream().filter(p -> !p.left).toList();
    }

    private int participants() {
        return (int) players.values().stream().filter(p -> !p.left && !p.spectator).count();
    }

    /** 방장 후보: 접속 중인 사람을 먼저, 없으면 남아 있는 아무나. */
    private String pickHost() {
        return live().stream().filter(o -> !o.sessions.isEmpty()).findFirst()
                .or(() -> live().stream().findFirst()).map(o -> o.token).orElse(null);
    }

    private boolean isLive(String token) {
        Player p = players.get(token);
        return p != null && !p.left;
    }

    private Player livePlayer(String token) {
        Player p = players.get(token);
        return p == null || p.left ? null : p;
    }

    private void cancelGrace(Player p) {
        p.graceGen++; // 이미 실행을 기다리는 오래된 타이머도 무효로 만든다
        if (p.grace != null) { p.grace.cancel(false); p.grace = null; }
    }

    private void cancelTimer() {
        if (timer != null) { timer.cancel(false); timer = null; }
    }

    private void system(String text) {
        out.convertAndSend("/topic/catch/chat", new Chat("system", 0, "", text));
    }

    private void toUser(String token, String dest, Object payload) {
        out.convertAndSendToUser(token, dest, payload);
    }

    private void broadcastState() {
        out.convertAndSend("/topic/catch/state", stateOf(++stateSeq));
    }

    private State stateOf() {
        return stateOf(stateSeq);
    }

    private State stateOf(long seq) {
        List<PlayerView> views = players.values().stream()
                .map(p -> new PlayerView(p.pid, p.nick, p.ready, p.score, !p.sessions.isEmpty(), p.left,
                        p.spectator, p.solved, p.token.equals(hostToken) && !p.left))
                .toList();
        // 도중에 나간 사람의 차례는 건너뛰므로, 진행 중에는 "지금까지 + 남은 유효한 차례"를 총 라운드로 보여 준다.
        int totalNow = (phase == Phase.DRAWING || phase == Phase.REVEAL)
                ? round + (int) queue.stream().filter(t -> { Player q = players.get(t); return q != null && !q.left && !q.spectator; }).count() // 새로고침 중(오프라인)인 사람도 센다
                : total;
        return new State(seq, phase.name(), SCORE, laps, seconds, round, totalNow,
                drawer == null ? 0 : drawer.pid, word == null ? 0 : word.length(),
                phase == Phase.DRAWING ? endsAt : 0, System.currentTimeMillis(),
                revealWord, solver == null ? 0 : solver.pid, views);
    }
}
