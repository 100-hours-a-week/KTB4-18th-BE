package com.muse.meomuneum.auth.controller;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.application.LocalMockLoginService;
import com.muse.meomuneum.auth.application.RefreshTokenCookieFactory;
import com.muse.meomuneum.auth.exception.LocalMockLoginForbiddenException;
import com.muse.meomuneum.auth.response.AuthSuccessCode;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("local-mock & !prod")
@ConditionalOnProperty(prefix = "auth.local-mock-login", name = "enabled", havingValue = "true", matchIfMissing = false)
public class LocalMockLoginController {

    private final LocalMockLoginService localMockLoginService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final Set<String> allowedOrigins;

    public LocalMockLoginController(LocalMockLoginService localMockLoginService,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            @Value("${auth.cors.allowed-origins}") String allowedOrigins) {
        this.localMockLoginService = localMockLoginService;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    @PostMapping("/local-mock-login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(HttpServletRequest request) {
        if (!isLoopbackOrigin(request.getHeader(HttpHeaders.ORIGIN)) || !isLoopbackPeer(request.getRemoteAddr())) {
            throw new LocalMockLoginForbiddenException();
        }

        LocalMockLoginService.LocalMockLoginToken token = localMockLoginService.issueToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
        TokenResponse response = new TokenResponse(token.accessToken(), token.expiresIn());
        return new ResponseEntity<>(ApiResponse.success(AuthSuccessCode.LOGIN_SUCCESS, response), headers,
                HttpStatus.OK);
    }

    private boolean isLoopbackOrigin(String origin) {
        if (origin == null || !allowedOrigins.contains(origin)) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                return false;
            }
            String host = uri.getHost();
            return host != null && ("localhost".equalsIgnoreCase(host) || isLoopbackAddress(host));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isLoopbackPeer(String remoteAddress) {
        return remoteAddress != null && isLoopbackAddress(remoteAddress);
    }

    private boolean isLoopbackAddress(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }
}
