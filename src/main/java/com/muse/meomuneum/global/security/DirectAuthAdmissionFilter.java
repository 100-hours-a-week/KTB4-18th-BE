package com.muse.meomuneum.global.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

public class DirectAuthAdmissionFilter extends OncePerRequestFilter {
    private final DirectAuthAdmissionFlag flag;
    private final SecurityErrorResponseWriter writer;

    public DirectAuthAdmissionFilter(DirectAuthAdmissionFlag flag, SecurityErrorResponseWriter writer) {
        this.flag = flag;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!flag.enabled() && "POST".equals(request.getMethod())
                && "direct-v2".equals(request.getHeader("X-Auth-Login-Protocol"))
                && ("/api/v1/auth/login".equals(path) || "/api/v1/auth/flows".equals(path))) {
            writer.write(request, response, SecurityErrorCode.LOGIN_PROTOCOL_DISABLED);
            return;
        }
        chain.doFilter(request, response);
    }
}
