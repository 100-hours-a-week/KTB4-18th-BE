package com.muse.meomuneum.auth.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.global.security.InternalSessionProtocol;

/** Terminates the internal protocol before user JWT, Origin or CSRF filters can inspect its credential. */
public final class CoreInternalAuthFilter extends OncePerRequestFilter {
    public static final String PATH = "/internal/v1/auth/sessions/validate";
    private final byte[] current;
    private final byte[] next;
    private final boolean nextEnabled;
    private final CoreSessionValidationService validation;
    private final Map<String, Window> callers = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public CoreInternalAuthFilter(String current, String next, CoreSessionValidationService validation) {
        this.current = credential(current);
        this.nextEnabled = next != null && !next.isBlank();
        this.next = next == null || next.isBlank() ? new byte[32] : credential(next);
        this.validation = validation;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().equals("/internal") && !request.getServletPath().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (!PATH.equals(request.getServletPath()) || !"POST".equals(request.getMethod())) {
            error(response, 403, "FORBIDDEN");
            return;
        }
        var headers = Collections.list(request.getHeaders("Authorization"));
        String header = headers.size() == 1 ? headers.getFirst() : "";
        byte[] supplied = header.startsWith("Bearer ") && header.length() <= 263
                ? header.substring(7).getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!(MessageDigest.isEqual(current, supplied) | (nextEnabled & MessageDigest.isEqual(next, supplied)))) {
            error(response, 401, "UNAUTHORIZED");
            return;
        }
        if (request.getQueryString() != null || !"application/json".equalsIgnoreCase(
                request.getContentType() == null ? "" : request.getContentType().split(";", 2)[0].trim())) {
            error(response, 400, "INVALID");
            return;
        }
        long now = System.nanoTime();
        callers.entrySet().removeIf(entry -> now - entry.getValue().started() > 2_000_000_000L);
        if (callers.size() > 21024 || !allow("task:" + request.getRemoteAddr(), now)) {
            error(response, 503, "UNAVAILABLE");
            return;
        }
        if (request.getContentLengthLong() > 32768) {
            error(response, 413, "TOO_LARGE");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(32769);
        if (body.length > 32768) {
            error(response, 413, "TOO_LARGE");
            return;
        }
        try {
            var pairs = InternalSessionProtocol.request(body);
            for (long user : pairs.stream().mapToLong(com.muse.meomuneum.global.security.SessionPair::userId).distinct()
                    .toArray()) {
                if (!allow("user:" + user, now)) {
                    error(response, 503, "UNAVAILABLE");
                    return;
                }
            }
            byte[] result = InternalSessionProtocol.encodeResponse(validation.validate(pairs));
            response.setStatus(200);
            response.getOutputStream().write(result);
        } catch (IllegalArgumentException invalid) {
            error(response, 400, "INVALID");
        } catch (RuntimeException unavailable) {
            error(response, 503, "UNAVAILABLE");
        }
    }

    private synchronized boolean allow(String caller, long now) {
        Window window = callers.get(caller);
        if (window == null || now - window.started() >= 1_000_000_000L) {
            callers.put(caller, new Window(now, 1));
            return true;
        }
        callers.put(caller, new Window(window.started(), window.count() + 1));
        return window.count() < 100;
    }

    private void error(HttpServletResponse response, int status, String suffix) throws IOException {
        response.setStatus(status);
        json.writeValue(response.getWriter(), ApiResponse.of("내부 인증 확인 실패",
                Map.of("code", "INTERNAL_AUTH_" + status + "_" + suffix)));
    }

    private byte[] credential(String value) {
        if (value == null || value.length() > 256 || !value.matches("[A-Za-z0-9._~+/-]{32,256}={0,2}")) {
            throw new IllegalArgumentException("internal service credential requires at least 32 bytes");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Window(long started, int count) {
    }
}
