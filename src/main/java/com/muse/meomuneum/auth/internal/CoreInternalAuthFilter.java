package com.muse.meomuneum.auth.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.global.security.InternalSessionProtocol;

/** Terminates the internal protocol before user JWT, Origin or CSRF filters can inspect its credential. */
public final class CoreInternalAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(CoreInternalAuthFilter.class);
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    public static final String PATH = "/internal/v1/auth/sessions/validate";
    private final byte[] current;
    private final byte[] next;
    private final boolean nextEnabled;
    private final CoreSessionValidationService validation;
    private final Map<String, Window> callers = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final ObjectMapper json = new ObjectMapper();

    public CoreInternalAuthFilter(String current, String next, CoreSessionValidationService validation) {
        this(current, next, validation, System::nanoTime);
    }

    public CoreInternalAuthFilter(String current, String next, CoreSessionValidationService validation,
            LongSupplier clock) {
        this.clock = clock;
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
        var taskHeaders = Collections.list(request.getHeaders("X-Chat-Task-Id"));
        String caller = "source:" + request.getRemoteAddr();
        if (!taskHeaders.isEmpty()) {
            String task = taskHeaders.getFirst();
            try {
                if (taskHeaders.size() != 1 || !UUID.fromString(task).toString().equals(task)) {
                    throw new IllegalArgumentException("invalid task identity");
                }
                caller = "task:" + task;
            } catch (IllegalArgumentException invalid) {
                error(response, 400, "INVALID");
                return;
            }
        }
        long now = clock.getAsLong();
        callers.entrySet().removeIf(entry -> now - entry.getValue().started() > 2_000_000_000L);
        if (callers.size() > 21024 || !allow(caller, now)) {
            unavailable(callers.size() > 21024 ? "caller_capacity" : "caller_rate", "none");
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
                    unavailable("user_rate", "none");
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
            unavailable("validation", unavailable.getClass().getSimpleName());
            error(response, 503, "UNAVAILABLE");
        }
    }

    private void unavailable(String stage, String cause) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("event=core_chat_auth_first_unavailable stage={} cause={}", stage, cause);
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
