package com.muse.meomuneum.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.application.AuthFlowService.LogoutReceipt;
import com.muse.meomuneum.auth.application.AuthService;
import com.muse.meomuneum.auth.application.CsrfTokenService;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.dto.CsrfTokenResponse;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.response.AuthSuccessCode;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CsrfTokenService csrfTokenService;
    private final AuthFlowService authFlowService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshReplayCipher replayCipher;

    @Autowired
    public AuthController(AuthService authService, CsrfTokenService csrfTokenService,
            AuthFlowService authFlowService, JwtTokenProvider jwtTokenProvider, RefreshReplayCipher replayCipher) {
        this.authService = authService;
        this.csrfTokenService = csrfTokenService;
        this.authFlowService = authFlowService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.replayCipher = replayCipher;

    }

    /** Keeps the standalone controller unit tests independent of the persistence boundary. */
    public AuthController(AuthService authService, CsrfTokenService csrfTokenService) {
        this(authService, csrfTokenService, null, null, null);
    }

    @GetMapping("/token/csrf")
    public ResponseEntity<ApiResponse<CsrfTokenResponse>> issueCsrfToken(HttpServletRequest request,
            HttpServletResponse servletResponse) {
        CsrfTokenResponse response = new CsrfTokenResponse(csrfTokenService.issueToken(request, servletResponse));
        return ResponseEntity.ok(ApiResponse.of("csrf token issued", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        TokenResponse tokenResponse;
        try {
            tokenResponse = authFlowService == null
                    ? authService.login(request, servletRequest, headers)
                    : authFlowService.withLockedFlow(servletRequest, flow -> {
                        var loginResult = authService.loginWithTicket(request, servletRequest, headers);
                        TokenResponse response = loginResult.response();
                        TokenClaims claims = jwtTokenProvider.parseAccessToken(response.accessToken());
                        try {
                            authFlowService.activateSession(servletRequest, claims.userId(), claims.sessionId(),
                                    loginResult.ticketId(), refreshCookie(headers), replayCipher, headers);
                        } catch (RuntimeException rejected) {
                            authFlowService.enqueueRejectedSessionCleanup(
                                    flow.flowId(), flow.flowEpoch(), claims.sessionId());
                            throw rejected;
                        }
                        return response;
                    });
        } catch (AuthenticationFailedException exception) {
            headers.getOrEmpty(HttpHeaders.SET_COOKIE)
                    .forEach(cookie -> servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie));
            headers.remove(HttpHeaders.SET_COOKIE);
            return new ResponseEntity<>(ApiResponse.failure(exception.getErrorCode().message()), headers,
                    exception.getErrorCode().status());
        }
        headers.getOrEmpty(HttpHeaders.SET_COOKIE)
                .forEach(cookie -> servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie));
        headers.remove(HttpHeaders.SET_COOKIE);
        return new ResponseEntity<>(ApiResponse.success(AuthSuccessCode.LOGIN_SUCCESS, tokenResponse), headers,
                AuthSuccessCode.LOGIN_SUCCESS.status());
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<?>> refresh(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        try {
            TokenResponse tokenResponse;
            if (authFlowService == null) {
                tokenResponse = authService.refresh(request, headers);
            } else {
                tokenResponse = authFlowService.withLockedFlow(request, flow -> {
                    if (flow.activeSessionSid() == null || !flow.authenticated()) {
                        throw new com.muse.meomuneum.auth.exception.AuthFlowException("AUTH_SESSION_INVALID");
                    }
                    authFlowService.assertCurrentSession(flow);
                    String operationId = request.getHeader("X-Refresh-Operation-Id");
                    String presentedRefreshToken = refreshCookie(request);
                    String replayToken = authFlowService.replayCommittedRefresh(flow, operationId,
                            presentedRefreshToken, replayCipher);
                    if (replayToken != null) {
                        authFlowService.writeTupleHeaders(headers, flow);
                        return authService.replayCommittedRefresh(replayToken, headers);
                    }
                    authFlowService.assertCurrentRefreshToken(flow, operationId, presentedRefreshToken);
                    TokenResponse refreshed = authService.refresh(request, headers);
                    authFlowService.commitRefresh(request, flow, operationId, presentedRefreshToken,
                            refreshCookie(headers), replayCipher, headers);
                    return refreshed;
                });
            }
            return new ResponseEntity<>(ApiResponse.of("token refreshed", tokenResponse), headers, HttpStatus.OK);
        } catch (AuthenticationFailedException exception) {
            return new ResponseEntity<>(ApiResponse.failure(exception.getErrorCode().message()), headers,
                    exception.getErrorCode().status());
        }
    }

    @PostMapping("/login/recovery")
    public ResponseEntity<ApiResponse<TokenResponse>> recoverLogin(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        TokenResponse response = authService.recoverLogin(request, headers);
        if (authFlowService != null) {
            TokenClaims claims = jwtTokenProvider.parseAccessToken(response.accessToken());
            authFlowService.assertLoginRecoverySession(request, claims.userId(), claims.sessionId(), headers);
        }
        return new ResponseEntity<>(ApiResponse.of("login recovered", response), headers, HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        try {
            if (authFlowService == null) {
                authService.logout(request, headers);
                return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
            }
            LogoutReceipt receipt = authFlowService.commitLogout(request, headers,
                    request.getHeader("Idempotency-Key"));
            try {
                authService.logout(request, headers);
            } catch (RuntimeException cleanupDeferred) {
                // The committed DB revocation is authoritative; the outbox retries AP cleanup.
            }
            authFlowService.expireAnonymousCookies(headers);
            return new ResponseEntity<>(ApiResponse.of("logout committed", new LogoutResponse(receipt)), headers,
                    HttpStatus.OK);
        } catch (AuthenticationFailedException exception) {
            return new ResponseEntity<>(ApiResponse.failure(exception.getErrorCode().message()), headers,
                    exception.getErrorCode().status());
        }
    }

    public record LogoutResponse(LogoutReceipt logoutReceipt) {
    }

    private String refreshCookie(HttpHeaders headers) {
        return headers.getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("refresh_token="))
                .map(value -> value.substring("refresh_token=".length()).split(";", 2)[0])
                .findFirst().orElse(null);
    }

    private String refreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if ("refresh_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
