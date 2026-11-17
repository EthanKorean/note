package com.ethan.note.toast;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/** 토스트를 받고 있는 브라우저 연결 수. 보냈는데 받는 화면이 없다는 걸 훅이 알 수 있게 응답에 담는다. */
@Component
public class Subscribers {
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent e) {
        StompHeaderAccessor acc = StompHeaderAccessor.wrap(e.getMessage());
        if (WebSocketConfig.TOPIC.equals(acc.getDestination())) sessions.add(acc.getSessionId());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent e) {
        sessions.remove(e.getSessionId());
    }

    public int count() {
        return sessions.size();
    }
}
