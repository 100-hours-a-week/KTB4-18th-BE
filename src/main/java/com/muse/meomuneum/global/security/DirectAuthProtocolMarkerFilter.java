package com.muse.meomuneum.global.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

public class DirectAuthProtocolMarkerFilter extends OncePerRequestFilter {
    private final SecurityErrorResponseWriter writer;

    public DirectAuthProtocolMarkerFilter(SecurityErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/auth/")) {
            List<String> markers = Collections.list(request.getHeaders("X-Auth-Login-Protocol"));
            if (!markers.isEmpty() && (markers.size() != 1 || !"direct-v2".equals(markers.get(0)))) {
                writer.write(request, response, SecurityErrorCode.INVALID_LOGIN_PROTOCOL);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
