package com.muse.meomuneum.global.security;

import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.exception.GlobalErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/token/refresh";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final String CSRF_PATH = "/api/v1/auth/token/csrf";

    private final JwtTokenProvider jwtTokenProvider;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String method = request.getMethod();
        return ("POST".equals(method) && (LOGIN_PATH.equals(requestUri) || REFRESH_PATH.equals(requestUri)
                || LOGOUT_PATH.equals(requestUri))) || ("GET".equals(method) && CSRF_PATH.equals(requestUri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!authorization.startsWith(BEARER_PREFIX)) {
            writeUnauthorizedResponse(response);
            return;
        }

        TokenClaims claims;
        try {
            claims = jwtTokenProvider.parseAccessToken(authorization.substring(BEARER_PREFIX.length()));
        } catch (AuthenticationFailedException exception) {
            writeUnauthorizedResponse(response);
            return;
        }

        List<SimpleGrantedAuthority> authorities = claims.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                claims.userId(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorizedResponse(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        errorResponseWriter.write(response, GlobalErrorCode.ACCESS_UNAUTHORIZED);
    }
}
