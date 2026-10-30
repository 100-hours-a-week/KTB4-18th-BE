package com.muse.meomuneum.auth.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthStateBusyException;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateExpiredException;
import com.muse.meomuneum.auth.exception.AuthStateTooEarlyException;
import com.muse.meomuneum.auth.exception.AuthStateTooManyRequestsException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.queue.AuthQueueCookieFactory;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

public final class QueuedLoginService {
    private static final Logger log = LoggerFactory.getLogger(QueuedLoginService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final UserAuthenticationService userAuthenticationService;
    private final HazelcastAuthStateStore authStateStore;
    private final RefreshReplayCipher replayCipher;
    private final AuthQueueCookieFactory queueCookieFactory;
    private final AuthStateProperties authStateProperties;

    public QueuedLoginService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService,
            HazelcastAuthStateStore authStateStore, RefreshReplayCipher replayCipher,
            AuthQueueCookieFactory queueCookieFactory, AuthStateProperties authStateProperties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.userAuthenticationService = userAuthenticationService;
        this.authStateStore = authStateStore;
        this.replayCipher = replayCipher;
        this.queueCookieFactory = queueCookieFactory;
        this.authStateProperties = authStateProperties;
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest servletRequest, HttpHeaders headers) {
        return centralizedLogin(request, servletRequest, headers).response();
    }

    public LoginResult loginWithTicket(LoginRequest request, HttpServletRequest servletRequest, HttpHeaders headers) {
        return centralizedLogin(request, servletRequest, headers);
    }

    public TokenResponse recoverLogin(HttpServletRequest request, HttpHeaders headers) {
        try (var ignored = authStateStore.acquireRequestPermit()) {
            String operationId = request.getHeader("X-Login-Operation-Id");
            if (operationId == null) {
                throw new AuthStateConflictException("LOGIN_RECOVERY_UNAVAILABLE");
            }
            try {
                UUID.fromString(operationId);
            } catch (IllegalArgumentException exception) {
                throw new AuthStateConflictException("LOGIN_RECOVERY_UNAVAILABLE");
            }
            String queueCookie = getOptionalCookie(request, authStateProperties.queueCookieName()).orElse(null);
            if (queueCookie == null) {
                throw new AuthStateConflictException("LOGIN_RECOVERY_UNAVAILABLE");
            }
            var session = authStateStore.loginRecoverySession(queueCookie, operationId);
            String refreshToken = replayCipher.decrypt(session.encryptedLoginRecoveryRefreshToken());
            User activeUser = user(session.userId());
            String accessToken = jwtTokenProvider.createAccessToken(activeUser, session.sid());
            refreshTokenCookieFactory.addRefreshTokenCookie(headers, refreshToken);
            return new TokenResponse(accessToken, jwtProperties.accessTokenExpirationSeconds());
        }
    }

    private LoginResult centralizedLogin(LoginRequest request, HttpServletRequest servletRequest,
            HttpHeaders headers) {
        String queueCookie = getOptionalCookie(servletRequest, authStateProperties.queueCookieName()).orElse(null);
        String correlationId = servletRequest.getHeader("X-Auth-Queue-Correlation");
        authStateStore.diagnosticLoginRequestStage("REQUEST_RECEIVED", correlationId,
                "RECEIVED", "NONE", 0, null);
        String operationId = loginStage(null, correlationId, "OPERATION_ID_VALIDATION", () -> {
            String value = servletRequest.getHeader("X-Login-Operation-Id");
            if (value == null) {
                throw new AuthStateConflictException("INVALID_OPERATION_ID");
            }
            try {
                UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                throw new AuthStateConflictException("INVALID_OPERATION_ID");
            }
            return value;
        });
        HazelcastAuthStateStore.Ticket ticket = null;
        boolean permitReleased = false;
        boolean invalidCredentialsObserved = false;
        try {
            String identityHash = loginStage(null, correlationId, "IDENTITY_FINGERPRINT",
                    () -> authStateStore.loginIdentityHash(request.email()));
            ticket = loginStage(null, correlationId, "TICKET_RESOLUTION_AND_CLAIM",
                    () -> authStateStore.claimReadyTicket(queueCookie, operationId, correlationId, identityHash));
            HazelcastAuthStateStore.Ticket claimedTicket = ticket;
            loginStage(ticket, correlationId, "PERMIT_OWNERSHIP_CHECK", () -> {
                if (!authStateStore.ownsBcryptPermit(claimedTicket.ticketId(), claimedTicket.bcryptPermitKey())) {
                    throw new AuthStateUnavailableException();
                }
                return null;
            });
            loginStage(ticket, correlationId, "IDENTITY_BIND", () -> {
                authStateStore.bindAuthenticationIdentity(claimedTicket, identityHash);
                return null;
            });
            User user;
            try {
                user = loginStage(ticket, correlationId, "CREDENTIALS_VERIFY",
                        () -> userAuthenticationService.authenticate(request.email(), request.password()));
            } catch (AuthenticationFailedException invalidCredentials) {
                invalidCredentialsObserved = true;
                loginStage(ticket, correlationId, "INVALID_CREDENTIALS_FINALIZE", () -> {
                    if (!authStateStore.ownsBcryptPermit(claimedTicket.ticketId(), claimedTicket.bcryptPermitKey())) {
                        throw new AuthStateUnavailableException();
                    }
                    authStateStore.markInvalidCredentials(claimedTicket);
                    authStateStore.completeTicket(claimedTicket,
                            HazelcastAuthStateStore.LoginOutcome.INVALID_CREDENTIALS, null);
                    queueCookieFactory.set(headers, queueCookie, 60);
                    return null;
                });
                throw invalidCredentials;
            }
            loginStage(ticket, correlationId, "AUTHENTICATED_USER_BIND", () -> {
                authStateStore.bindAuthenticatedUser(claimedTicket, user.getId());
                return null;
            });
            // The canonical flow activation transaction revokes the previously attached sid.
            // Hazelcast cleanup is emitted through the outbox after that database commit.
            String sid = ticket.sid();
            String refreshToken = loginStage(ticket, correlationId, "REFRESH_TOKEN_ISSUE",
                    () -> jwtTokenProvider.createRefreshToken(user, sid));
            RefreshTokenClaims refreshClaims = loginStage(ticket, correlationId, "REFRESH_TOKEN_VALIDATE",
                    () -> jwtTokenProvider.parseRefreshToken(refreshToken));
            String accessToken = loginStage(ticket, correlationId, "ACCESS_TOKEN_ISSUE",
                    () -> jwtTokenProvider.createAccessToken(user, sid));
            TokenClaims accessClaims = loginStage(ticket, correlationId, "ACCESS_TOKEN_VALIDATE",
                    () -> jwtTokenProvider.parseAccessToken(accessToken));
            Instant absoluteExpiry = loginStage(ticket, correlationId, "SESSION_EXPIRY_RESOLVE",
                    () -> authStateStore.newAbsoluteSessionExpiry());
            var session = loginStage(ticket, correlationId, "SESSION_PROVISION",
                    () -> authStateStore.createSession(user.getId(), sid, refreshClaims.tokenId(),
                            authStateStore.tokenHash(refreshToken), operationId, replayCipher.encrypt(refreshToken),
                            accessClaims.expiresAt(), absoluteExpiry,
                            deviceLabel(servletRequest.getHeader("User-Agent")), claimedTicket.ticketId(),
                            claimedTicket.claimId(), claimedTicket.claimLeaseUntil()));
            String canonicalRefreshToken = loginStage(ticket, correlationId, "SESSION_CANONICALIZE", () -> {
                String canonical = replayCipher.decrypt(session.encryptedLoginRecoveryRefreshToken());
                if (!authStateStore.tokenHash(canonical).equals(session.currentRefreshHash())) {
                    throw new AuthStateUnavailableException();
                }
                return canonical;
            });
            loginStage(ticket, correlationId, "TICKET_CONSUME", () -> {
                authStateStore.completeTicket(claimedTicket,
                        HazelcastAuthStateStore.LoginOutcome.SUCCESS, session.sid());
                return null;
            });
            loginStage(ticket, correlationId, "RESPONSE_COOKIE_WRITE", () -> {
                refreshTokenCookieFactory.addRefreshTokenCookie(headers, canonicalRefreshToken);
                queueCookieFactory.set(headers, queueCookie, 900);
                return null;
            });
            TokenResponse response = loginStage(ticket, correlationId, "LOGIN_RESPONSE",
                    () -> new TokenResponse(accessToken, jwtProperties.accessTokenExpirationSeconds()));
            return new LoginResult(response, claimedTicket.ticketId());
        } catch (RuntimeException exception) {
            if (ticket != null) {
                long recoveryStarted = System.nanoTime();
                try {
                    authStateStore.releaseBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey());
                    permitReleased = true;
                    authStateStore.reconcileFailedLogin(ticket, invalidCredentialsObserved
                            ? HazelcastAuthStateStore.LoginOutcome.INVALID_CREDENTIALS
                            : null);
                    authStateStore.diagnosticLoginStage("EXCEPTION_RECONCILE", ticket.ticketId(),
                            "COMPLETE", diagnosticErrorCode(exception),
                            elapsedMillis(recoveryStarted), null);
                } catch (RuntimeException recoveryFailure) {
                    authStateStore.diagnosticLoginStage("EXCEPTION_RECONCILE", ticket.ticketId(), "ERROR",
                            "AUTH_STATE_UNAVAILABLE", elapsedMillis(recoveryStarted), recoveryFailure);
                    exception.addSuppressed(recoveryFailure);
                }
            }
            authStateStore.diagnosticLoginRequestStage("LOGIN_REQUEST_TERMINAL", correlationId,
                    "ERROR", diagnosticErrorCode(exception), 0, exception);
            throw exception;
        } finally {
            if (ticket != null && !permitReleased) {
                authStateStore.releaseBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey());
            }
        }
    }

    public record LoginResult(TokenResponse response, String ticketId) {
    }

    private <T> T loginStage(HazelcastAuthStateStore.Ticket ticket, String correlationId, String stage,
            Supplier<T> action) {
        long started = System.nanoTime();
        try {
            T result = action.get();
            if (ticket != null) {
                authStateStore.diagnosticLoginStage(stage, ticket.ticketId(), "SUCCESS", "NONE",
                        elapsedMillis(started), null);
            } else {
                authStateStore.diagnosticLoginRequestStage(stage, correlationId, "SUCCESS", "NONE",
                        elapsedMillis(started), null);
            }
            return result;
        } catch (RuntimeException failure) {
            String errorCode = diagnosticErrorCode(failure);
            if (ticket != null) {
                authStateStore.diagnosticLoginStage(stage, ticket.ticketId(), "ERROR", errorCode,
                        elapsedMillis(started), failure);
            } else {
                authStateStore.diagnosticLoginRequestStage(stage, correlationId, "ERROR", errorCode,
                        elapsedMillis(started), failure);
            }
            throw failure;
        }
    }

    private String diagnosticErrorCode(RuntimeException failure) {
        if (failure instanceof AuthStateConflictException conflict) {
            return conflict.code();
        }
        if (failure instanceof AuthStateTooEarlyException tooEarly) {
            return tooEarly.code();
        }
        if (failure instanceof AuthStateTooManyRequestsException tooManyRequests) {
            return tooManyRequests.code();
        }
        if (failure instanceof AuthenticationFailedException) {
            return "INVALID_CREDENTIALS";
        }
        if (failure instanceof AuthStateExpiredException) {
            return "TICKET_EXPIRED";
        }
        if (failure instanceof AuthStateUnavailableException || failure instanceof AuthStateBusyException) {
            return "AUTH_STATE_UNAVAILABLE";
        }
        return "OTHER";
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedNanos));
    }

    private String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }
        String browser = userAgent.contains("Edg/")
                ? "Edge"
                : userAgent.contains("Firefox/")
                        ? "Firefox"
                        : userAgent.contains("Chrome/")
                                ? "Chrome"
                                : userAgent.contains("Safari/") ? "Safari" : "Other browser";
        String platform = userAgent.contains("Android")
                ? "Android"
                : userAgent.contains("iPhone") || userAgent.contains("iPad")
                        ? "iOS"
                        : userAgent.contains("Windows")
                                ? "Windows"
                                : userAgent.contains("Mac OS")
                                        ? "macOS"
                                        : userAgent.contains("Linux") ? "Linux" : "device";
        return browser + " on " + platform;
    }

    private Optional<String> getOptionalCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> name.equals(cookie.getName()))
                .map(cookie -> cookie.getValue()).filter(value -> !value.isBlank()).findFirst();
    }

    private User user(Long userId) {
        try {
            return userAuthenticationService.findActiveUser(userId);
        } catch (AuthenticationFailedException exception) {
            log.warn("event=auth_refresh_rejected reason={} requestId={}", "user_unavailable",
                    org.slf4j.MDC.get("requestId"));
            throw exception;
        }
    }
}
