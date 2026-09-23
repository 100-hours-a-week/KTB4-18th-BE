package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.config.JwtProperties;

class JwtAuthenticationFilterTest {

    private static final String JWT_SECRET = "development-only-secret-with-at-least-32-bytes";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JwtTokenProvider jwtTokenProvider;
    private FilterChain filterChain;
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        filterChain = mock(FilterChain.class);
        jwtAuthenticationFilter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                new SecurityErrorResponseWriter(objectMapper)
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAuthorizationHeaderWithoutBearerPrefix() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Basic credentials");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(mock(Authentication.class));

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        assertUnauthorizedResponse(response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(filterChain, jwtTokenProvider);
    }

    @Test
    void passesRequestWithoutAuthorizationHeaderToNextFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void rejectsMalformedBearerTokenWithoutCallingFilterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer malformed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenProvider.parseAccessToken("malformed-token"))
                .thenThrow(new AuthenticationFailedException(SecurityErrorCode.ACCESS_UNAUTHORIZED));

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        assertUnauthorizedResponse(response);
        verify(jwtTokenProvider).parseAccessToken("malformed-token");
        verifyNoInteractions(filterChain);
    }

    @Test
    void rejectsEmptyBearerTokenWithoutCallingFilterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenProvider.parseAccessToken(""))
                .thenThrow(new AuthenticationFailedException(SecurityErrorCode.ACCESS_UNAUTHORIZED));

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        assertUnauthorizedResponse(response);
        verify(jwtTokenProvider).parseAccessToken("");
        verifyNoInteractions(filterChain);
    }

    @Test
    void rejectsExpiredAccessTokenWithCommonUnauthorizedResponse() throws Exception {
        JwtTokenProvider actualTokenProvider = new JwtTokenProvider(
                new JwtProperties(
                        "project-api",
                        "project-api",
                        JWT_SECRET,
                        3600,
                        1209600
                )
        );
        String expiredToken = createExpiredAccessToken();
        jwtAuthenticationFilter = new JwtAuthenticationFilter(
                actualTokenProvider,
                new SecurityErrorResponseWriter(objectMapper)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer " + expiredToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        assertUnauthorizedResponse(response);
        verifyNoInteractions(filterChain);
    }

    @Test
    void passesValidAccessTokenWithAuthenticatedSecurityContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenProvider.parseAccessToken("valid-token"))
                .thenReturn(new TokenClaims(1L, List.of("USER")));
        doAnswer(invocation -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.getPrincipal()).isEqualTo(1L);
            assertThat(authentication.getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_USER");
            return null;
        }).when(filterChain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @ParameterizedTest
    @MethodSource("publicAuthenticationEndpoints")
    void skipsJwtValidationForPublicAuthenticationEndpoint(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtTokenProvider);
    }

    private static Stream<Arguments> publicAuthenticationEndpoints() {
        return Stream.of(
                Arguments.of("POST", "/api/v1/auth/login"),
                Arguments.of("POST", "/api/v1/auth/token/refresh"),
                Arguments.of("POST", "/api/v1/auth/logout"),
                Arguments.of("GET", "/api/v1/auth/token/csrf")
        );
    }

    private void assertUnauthorizedResponse(MockHttpServletResponse response) throws Exception {
        JsonNode responseBody = objectMapper.readTree(response.getContentAsByteArray());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(responseBody.path("message").asText()).isEqualTo("unauthorized");
        assertThat(responseBody.path("data").isNull()).isTrue();
    }

    private String createExpiredAccessToken() throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("project-api")
                .audience("project-api")
                .subject("1")
                .issueTime(Date.from(now.minusSeconds(240)))
                .expirationTime(Date.from(now.minusSeconds(120)))
                .jwtID("expired-access-token")
                .claim("type", "ACCESS")
                .claim("roles", List.of("USER"))
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }
}
