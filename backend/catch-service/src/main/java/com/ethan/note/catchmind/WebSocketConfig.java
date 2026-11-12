package com.ethan.note.catchmind;

import java.net.URI;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * STOMP over WebSocket. 브라우저는 gateway 의 /ws-catch 로 접속한다.
 * 접속할 때 쿼리의 token 을 사용자(Principal) 이름으로 삼는다. 새로고침해도 같은 token 으로 접속하면
 * 같은 사람으로 이어지고, token 은 본인에게만 오는 /user/queue/** (제시어 등)의 주소 역할을 한다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** 브로커 하트비트용. 설정 순환을 피하려고 늦게 가져온다(스프링 문서의 권장 방식). */
    @Autowired
    @Lazy
    private TaskScheduler messageBrokerTaskScheduler; // 이름이 스프링 기본 빈 이름과 같아야 후보가 둘이어도 고른다

    private static final Pattern TOKEN = Pattern.compile("(?:^|&)token=([A-Za-z0-9-]{16,64})(?:&|$)");

    record TokenPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-catch")
                .setAllowedOriginPatterns("*") // 스터디용: vite·gateway 를 거치는 Origin 이 달라 모두 허용
                .setHandshakeHandler(new DefaultHandshakeHandler() {
                    @Override
                    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler,
                                                      Map<String, Object> attributes) {
                        URI uri = request.getURI();
                        String q = uri.getRawQuery();
                        Matcher m = q == null ? null : TOKEN.matcher(q);
                        return new TokenPrincipal(m != null && m.find() ? m.group(1) : UUID.randomUUID().toString());
                    }
                });
    }

    /**
     * 클라이언트가 /topic·/queue 로 직접 SEND 하면 브로커가 그대로 모두에게 뿌려 버린다(그림·정답·상태 위조).
     * 클라이언트의 SEND 는 /app/** 만, SUBSCRIBE 는 /topic/catch/** 와 본인 몫(/user/queue/**)만 허용하고 나머지는 버린다.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 한 스레드로 처리해 클라이언트가 보낸 순서(획 조각, 지우기, 입장 → Ready)가 그대로 방에 반영되게 한다.
        registration.taskExecutor().corePoolSize(1).maxPoolSize(1);
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = StompHeaderAccessor.wrap(message);
                String dest = acc.getDestination();
                if (StompCommand.SEND.equals(acc.getCommand())) {
                    if (dest == null || !dest.startsWith("/app/")) return null;
                } else if (StompCommand.SUBSCRIBE.equals(acc.getCommand())) {
                    // 허용 목록만 구독할 수 있다. 그렇지 않으면 /queue/** 같은 패턴으로 남의 개인 큐(제시어)를 엿볼 수 있다.
                    boolean ok = dest != null && !dest.contains("*") && !dest.contains("..")
                            && (dest.startsWith("/topic/catch/") || dest.startsWith("/user/queue/"));
                    if (!ok) return null;
                }
                return message;
            }
        });
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setPreservePublishOrder(true); // 상태·그림 메시지가 보낸 순서대로 도착하게 한다
        // 하트비트로 노트북 절전·네트워크 단절처럼 소켓만 남은 연결을 찾아 끊는다(그래야 퇴장 유예가 시작된다).
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{20000, 30000}) // 받는 쪽 허용 30초(스프링은 이 간격의 3배 동안 조용하면 끊는다 → 약 90초). 백그라운드 탭의 1분 주기 하트비트는 통과한다
                .setTaskScheduler(messageBrokerTaskScheduler); // 스프링이 관리하는 기본 스케줄러(종료도 스프링이 처리한다)
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
