package com.muse.meomuneum.feature.auth.login.controller;

import com.muse.meomuneum.feature.auth.login.dto.LoginRequest;
import com.muse.meomuneum.feature.auth.login.dto.LoginResponse;
import com.muse.meomuneum.feature.auth.login.response.ApiResponse;
import com.muse.meomuneum.feature.auth.login.service.LoginService;
import com.muse.meomuneum.feature.auth.login.token.IssuedTokens;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginControllerTest {

    @Test
    void returnsOnlyAccessTokenFieldsInBodyAndRefreshTokenInCookie() {
        LoginService loginService = mock(LoginService.class);
        LoginRequest request = new LoginRequest("member@example.com", "password123");
        when(loginService.login(request)).thenReturn(new IssuedTokens("access-token", 3600, "refresh-token", 1209600));
        LoginController controller = new LoginController(loginService);

        ResponseEntity<ApiResponse<LoginResponse>> response = controller.login(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("login success", response.getBody().message());
        assertEquals("access-token", response.getBody().data().accessToken());
        assertEquals(3600, response.getBody().data().expiresIn());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains("refresh_token=refresh-token"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertFalse(response.getBody().toString().contains("refresh-token"));
    }
}
