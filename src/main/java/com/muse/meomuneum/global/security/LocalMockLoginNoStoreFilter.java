package com.muse.meomuneum.global.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

public class LocalMockLoginNoStoreFilter extends OncePerRequestFilter {

    private static final String LOCAL_MOCK_LOGIN_PATH = "/api/v1/auth/local-mock-login";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!LOCAL_MOCK_LOGIN_PATH.equals(request.getRequestURI())) {
            return true;
        }
        return !"POST".equalsIgnoreCase(request.getMethod()) && !"OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        filterChain.doFilter(request, response);
    }
}
