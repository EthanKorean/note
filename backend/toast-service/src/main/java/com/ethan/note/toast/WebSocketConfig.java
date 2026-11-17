package com.ethan.note.toast;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** STOMP over WebSocket. 브라우저는 vite 프록시(/ws-toast)로 접속하고 /topic/toast 만 구독한다. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    public static final String TOPIC = "/topic/toast";

    /** 브로커 하트비트용. 설정 순환을 피하려고 늦게 가져온다(catch-service 와 같은 방식). */
    @Autowired
    @Lazy
    private TaskScheduler messageBrokerTaskScheduler;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 알림에 작업 내용 일부가 들어가므로 아무 사이트나 붙어서 엿보지 못하게, 이 컴퓨터의 localhost 화면(Origin)만 허용한다.
        registry.addEndpoint("/ws-toast")
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*");
    }

    /** 알림은 서버 → 브라우저 한 방향이다. 브라우저의 SEND 는 모두 버리고 구독도 /topic/toast 하나만 허용한다(알림 위조 방지). */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = StompHeaderAccessor.wrap(message);
                if (StompCommand.SEND.equals(acc.getCommand())) return null;
                if (StompCommand.SUBSCRIBE.equals(acc.getCommand()) && !TOPIC.equals(acc.getDestination())) return null;
                return message;
            }
        });
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 하트비트로 노트북 절전 뒤처럼 소켓만 남은 연결을 찾아 끊는다(그래야 브라우저가 다시 접속한다).
        // 받는 쪽 허용을 30초로 둔다(스프링은 이 간격의 3배 동안 조용하면 끊는다 → 약 90초). 알림이 필요한 순간은 탭이 가려져 있을 때인데,
        // 브라우저가 가려진 탭의 타이머를 1분에 한 번으로 늦춰도(클라이언트 하트비트) 연결이 끊기지 않는다. catch-service 와 같은 값.
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{20000, 30000})
                .setTaskScheduler(messageBrokerTaskScheduler);
    }
}
