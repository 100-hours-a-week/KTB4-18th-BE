package com.muse.meomuneum.feature.auth.login.controller;

import com.muse.meomuneum.feature.auth.login.dto.LoginRequest;
import com.muse.meomuneum.feature.auth.login.dto.LoginResponse;
import com.muse.meomuneum.feature.auth.login.response.ApiResponse;
import com.muse.meomuneum.feature.auth.login.service.LoginService;
import com.muse.meomuneum.feature.auth.login.token.IssuedTokens;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    private final LoginService loginService;

    public LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    @RequestMapping(value = "/api/v1/auth/login", method = RequestMethod.POST)
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        IssuedTokens tokens = loginService.login(request);
        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, tokens.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(tokens.refreshTokenExpiresIn())
                .build();
        LoginResponse response = new LoginResponse(tokens.accessToken(), tokens.accessTokenExpiresIn());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponse.success("login success", response));
    }
}
