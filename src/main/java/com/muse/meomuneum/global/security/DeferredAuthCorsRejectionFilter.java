package com.muse.meomuneum.global.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

public class DeferredAuthCorsRejectionFilter extends OncePerRequestFilter {
    private final SecurityErrorResponseWriter writer;

    public DeferredAuthCorsRejectionFilter(SecurityErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (Boolean.TRUE.equals(request.getAttribute(DirectAuthCorsProcessor.REJECTED_ORIGIN))) {
            writer.write(request, response, "direct-v2".equals(request.getHeader("X-Auth-Login-Protocol"))
                    ? SecurityErrorCode.ORIGIN_NOT_ALLOWED
                    : SecurityErrorCode.AUTH_ORIGIN_DENIED);
            return;
        }
        chain.doFilter(request, response);
    }
}
