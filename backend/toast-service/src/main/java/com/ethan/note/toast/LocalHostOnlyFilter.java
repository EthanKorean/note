package com.ethan.note.toast;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DNS 리바인딩 방어. 공격자 도메인이 127.0.0.1 로 풀리게 만들면 다른 사이트의 페이지가 이 서버에 같은 출처 요청을 보낼 수 있는데,
 * 그 요청의 Host 는 공격자 도메인이므로 localhost 계열이 아니면 거부한다. (웹소켓은 Origin 으로도 막는다)
 */
@Component
public class LocalHostOnlyFilter extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String host = req.getHeader("Host");
        String name = host == null ? "" : host.replaceFirst(":\\d+$", "").toLowerCase(Locale.ROOT); // "[::1]:9208" 도 "[::1]" 로
        if (!HOSTS.contains(name)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN, "허용되지 않은 Host");
            return;
        }
        chain.doFilter(req, res);
    }
}
