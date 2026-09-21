package com.muse.meomuneum.feature.auth.login.controller;

import com.muse.meomuneum.feature.auth.login.InvalidCredentialsException;
import com.muse.meomuneum.feature.auth.login.response.LoginExceptionHandler;
import com.muse.meomuneum.feature.auth.login.service.LoginService;
import com.muse.meomuneum.feature.auth.login.token.IssuedTokens;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginControllerWebMvcTest {

    private final LoginService loginService = mock(LoginService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new LoginController(loginService))
                .setControllerAdvice(new LoginExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void returnsTheLoginResponseContractAndRefreshTokenCookie() throws Exception {
        when(loginService.login(any())).thenReturn(new IssuedTokens(
                "access-token", 3600, "refresh-token", 1209600));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login-test@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("login success"))
                .andExpect(jsonPath("$.data.access_token").value("access-token"))
                .andExpect(jsonPath("$.data.expires_in").value(3600))
                .andExpect(jsonPath("$.data.refresh_token").doesNotExist())
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().secure("refresh_token", true))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Path=/api/v1/auth")));
    }

    @Test
    void returnsInvalidRequestForMalformedRequestValues() throws Exception {
        String[] invalidBodies = {
                "{}",
                "{\"email\":null,\"password\":\"password\"}",
                "{\"email\":\"\",\"password\":\"password\"}",
                "{\"email\":\"not-an-email\",\"password\":\"password\"}",
                "{\"email\":\"login-test@example.com\",\"password\":\"\"}",
                "{"
        };

        for (String invalidBody : invalidBodies) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("invalid request"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    void returnsCredentialFailureWithoutCookies() throws Exception {
        when(loginService.login(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login-test@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(cookie().doesNotExist("refresh_token"));
    }

    @Test
    void returnsCommonInternalServerErrorForUnexpectedFailure() throws Exception {
        when(loginService.login(any())).thenThrow(new IllegalStateException("unexpected failure"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login-test@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("internal server error"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
