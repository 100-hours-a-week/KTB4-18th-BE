package com.muse.meomuneum.auth.controller;

import java.time.Duration;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.QueueStatusResponse;
import com.muse.meomuneum.auth.exception.AuthStateOperationInProgressException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth/login-queue/tickets")
public class LoginQueueController {

    private static final String COOKIE_PATH = "/api/v1/auth";
    private final HazelcastAuthStateStore store;
    private final AuthStateProperties properties;
    private final boolean secureCookie;
    private final String sameSite;

    public LoginQueueController(HazelcastAuthStateStore store, AuthStateProperties properties,
            @Value("${auth.cookie.secure:true}") boolean secureCookie,
            @Value("${auth.cookie.same-site:Lax}") String sameSite) {
        this.store = store;
        this.properties = properties;
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request) {
        store.enforceTicketCreateRateLimit(request.getRemoteAddr());
        String existingCookie = cookieValue(request);
        try {
            var result = store.createOrReuseTicket(existingCookie, idempotencyKey);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, queueCookie(result.queueCookie(), properties.queueCookieSeconds()))
                    .body(ApiResponse.of("login queue ticket", response(result.view())));
        } catch (AuthStateOperationInProgressException exception) {
            if (exception.queueCookie() == null) {
                return ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .body(ApiResponse.failure("OPERATION_IN_PROGRESS"));
            }
            var waiting = new HazelcastAuthStateStore.TicketView(
                    HazelcastAuthStateStore.TicketStatus.WAITING, null, null, 6, null);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE,
                            queueCookie(exception.queueCookie(), properties.queueCookieSeconds()))
                    .body(ApiResponse.of("login queue ticket", response(waiting)));
        }
    }

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<?>> current(HttpServletRequest request) {
        try {
            HazelcastAuthStateStore.TicketView view = store.currentTicket(cookieValue(request));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(ApiResponse.of("login queue status", response(view)));
        } catch (com.muse.meomuneum.auth.exception.AuthStateExpiredException exception) {
            return ResponseEntity.status(410).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, queueCookie("", 0))
                    .body(ApiResponse.failure("TICKET_EXPIRED"));
        } catch (com.muse.meomuneum.auth.exception.AuthStateNotFoundException exception) {
            return ResponseEntity.status(404).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, queueCookie("", 0))
                    .body(ApiResponse.failure(exception.code()));
        }
    }

    @DeleteMapping("/current")
    public ResponseEntity<ApiResponse<Void>> cancel(HttpServletRequest request) {
        store.cancelCurrentTicket(cookieValue(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, queueCookie("", 0)).build();
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.queueCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private QueueStatusResponse response(HazelcastAuthStateStore.TicketView view) {
        return new QueueStatusResponse(view.status(), view.loginOutcome(), view.usersAhead(), view.nextPollSeconds(),
                store.diagnosticCorrelationId(view.ticketId()));
    }

    private String queueCookie(String value, long maxAge) {
        return ResponseCookie.from(properties.queueCookieName(), value).httpOnly(true).secure(secureCookie)
                .sameSite(sameSite).path(COOKIE_PATH).maxAge(Duration.ofSeconds(maxAge)).build().toString();
    }

}
