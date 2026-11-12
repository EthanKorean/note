package com.ethan.note.catchmind;

import com.ethan.note.catchmind.Messages.*;
import java.security.Principal;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** STOMP 메시지를 받아 방으로 넘긴다. 보낸 사람은 Principal(접속 token)로 식별한다. */
@Controller
public class CatchController {
    private final GameRoom room;

    public CatchController(GameRoom room) {
        this.room = room;
    }

    @MessageMapping("/catch/join")
    public void join(@Payload JoinReq r, Principal who, @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String sid) {
        if (r != null) room.join(who.getName(), sid, r.nick());
    }

    @MessageMapping("/catch/hello")
    public void hello(Principal who) {
        room.hello(who.getName());
    }

    @MessageMapping("/catch/leave")
    public void leave(Principal who) {
        room.leave(who.getName());
    }

    @MessageMapping("/catch/ready")
    public void ready(@Payload ReadyReq r, Principal who) {
        if (r != null) room.ready(who.getName(), r.ready());
    }

    @MessageMapping("/catch/options")
    public void options(@Payload OptionsReq r, Principal who) {
        if (r != null) room.options(who.getName(), r.laps(), r.seconds());
    }

    @MessageMapping("/catch/stroke")
    public void stroke(@Payload StrokeReq r, Principal who) {
        room.stroke(who.getName(), r);
    }

    @MessageMapping("/catch/clear")
    public void clear(Principal who) {
        room.clear(who.getName());
    }

    @MessageMapping("/catch/guess")
    public void guess(@Payload GuessReq r, Principal who) {
        if (r != null) room.guess(who.getName(), r.text());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent e) {
        room.disconnected(e.getSessionId());
    }
}
