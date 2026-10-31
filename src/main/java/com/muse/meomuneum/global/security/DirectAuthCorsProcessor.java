package com.muse.meomuneum.global.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

public class DirectAuthCorsProcessor extends DefaultCorsProcessor {
    public static final String REJECTED_ORIGIN = DirectAuthCorsProcessor.class.getName() + ".rejected";

    @Override
    public boolean processRequest(CorsConfiguration config, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        boolean auth = request.getRequestURI().startsWith("/api/v1/auth/");
        boolean preflight = "OPTIONS".equals(request.getMethod()) && !origins.isEmpty()
                && request.getHeader("Access-Control-Request-Method") != null;
        boolean invalid = !origins.isEmpty() && (origins.size() != 1 || config == null
                || !config.getAllowedOrigins().contains(origins.get(0)));
        if (auth && invalid) {
            if (preflight) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            request.setAttribute(REJECTED_ORIGIN, Boolean.TRUE);
            return true;
        }
        return super.processRequest(config, request, response);
    }
}
