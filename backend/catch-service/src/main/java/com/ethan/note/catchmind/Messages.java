package com.ethan.note.catchmind;

import java.util.List;

/** 클라이언트와 주고받는 메시지 모양. */
final class Messages {
    // ---- 클라이언트 → 서버 (/app/catch/**) ----
    record JoinReq(String nick) {}
    record ReadyReq(boolean ready) {}
    record OptionsReq(int laps, int seconds) {}
    /** 획의 한 조각. sid 는 획 번호, c 는 색 번호(-1 = 지우개), pts 는 0~1 로 정규화한 좌표 [x, y]. */
    record StrokeReq(int sid, int c, int size, double[][] pts) {}
    record GuessReq(String text) {}

    // ---- 서버 → 클라이언트 ----
    /** /topic/catch/draw : type 이 stroke 면 획 조각, clear 면 전체 지우기. */
    record Draw(String type, int sid, int c, int size, double[][] pts) {}
    /** /topic/catch/chat : kind = chat | system | correct */
    record Chat(String kind, int pid, String nick, String text) {}
    /** /user/queue/me : 입장 결과. error 가 있으면 입장 실패. word 는 출제자에게만 간다. seq 는 이 응답 직후에 나갈 상태 번호(그보다 오래된 상태 메시지는 무시한다). */
    record Me(Integer pid, String error, String word, long seq) {}
    /** /user/queue/strokes : 늦게 들어온 사람에게 지금까지의 그림을 한 번에 보낸다. */
    record Strokes(List<Draw> list) {}

    record PlayerView(int pid, String nick, boolean ready, int score, boolean online, boolean left,
                      boolean spectator, boolean solved, boolean host) {}

    /** /topic/catch/state : 모두에게 같은 내용. 제시어는 REVEAL 단계에서만 담는다. */
    record State(long seq, String phase, int win, int laps, int seconds, int round, int total, int drawerPid, int wordLen,
                 long endsAt, long serverNow, String revealWord, int solverPid, List<PlayerView> players) {}

    private Messages() {}
}
