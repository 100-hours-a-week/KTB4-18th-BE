package com.muse.meomuneum.auth.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.application.AuthService;
import com.muse.meomuneum.auth.application.RefreshTokenCookieFactory;
import com.muse.meomuneum.auth.dto.DeviceSession;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;

@RestController
@RequestMapping("/api/v1/auth/sessions")
public class AuthSessionController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenCookieFactory refreshCookieFactory;

    public AuthSessionController(AuthService authService, JwtTokenProvider jwtTokenProvider,
            RefreshTokenCookieFactory refreshCookieFactory) {
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceSession>>> list(Authentication authentication,
            HttpServletRequest request) {
        Long userId = principalUserId(authentication);
        String currentSid = accessClaims(request).map(TokenClaims::sessionId).orElse(null);
        List<DeviceSession> sessions = authService.listSessions(userId).stream()
                .map(session -> new DeviceSession(session.sid(), currentSid != null && currentSid.equals(session.sid()),
                        session.userAgentSummary(), session.createdAt(), session.lastRefreshedAt()))
                .toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of("sessions retrieved", sessions));
    }

    @DeleteMapping("/{sid}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable String sid, Authentication authentication,
            HttpServletRequest request) {
        Long userId = principalUserId(authentication);
        authService.revokeSession(userId, sid);
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        String currentSid = accessClaims(request).map(TokenClaims::sessionId).orElse(null);
        if (sid.equals(currentSid)) {
            refreshCookieFactory.deleteRefreshTokenCookie(headers);
        }
        return ResponseEntity.noContent().headers(headers).build();
    }

    private Long principalUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
        }
        return userId;
    }

    private java.util.Optional<TokenClaims> accessClaims(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(jwtTokenProvider.parseAccessToken(header.substring(7)));
    }

}
