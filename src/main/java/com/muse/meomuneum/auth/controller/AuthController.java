package com.muse.meomuneum.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.auth.dto.CsrfTokenResponse;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.response.AuthSuccessCode;
import com.muse.meomuneum.auth.service.AuthService;
import com.muse.meomuneum.auth.service.CsrfTokenService;
import com.muse.meomuneum.global.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CsrfTokenService csrfTokenService;

    public AuthController(AuthService authService, CsrfTokenService csrfTokenService) {
        this.authService = authService;
        this.csrfTokenService = csrfTokenService;
    }

    @GetMapping("/token/csrf")
    public ResponseEntity<ApiResponse<CsrfTokenResponse>> issueCsrfToken(HttpServletRequest request) {
        CsrfTokenResponse response = new CsrfTokenResponse(csrfTokenService.issueToken(request));
        return ResponseEntity.ok(ApiResponse.of("csrf token issued", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        HttpHeaders headers = new HttpHeaders();
        TokenResponse tokenResponse = authService.login(request, servletRequest, headers);
        return new ResponseEntity<>(
                ApiResponse.success(AuthSuccessCode.LOGIN_SUCCESS, tokenResponse),
                headers,
                AuthSuccessCode.LOGIN_SUCCESS.status());
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<?>> refresh(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        try {
            TokenResponse tokenResponse = authService.refresh(request, headers);
            return new ResponseEntity<>(ApiResponse.of("token refreshed", tokenResponse), headers, HttpStatus.OK);
        } catch (AuthenticationFailedException exception) {
            return new ResponseEntity<>(
                    ApiResponse.failure(exception.getErrorCode().message()),
                    headers,
                    exception.getErrorCode().status()
            );
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        authService.logout(headers);
        return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
    }
}
