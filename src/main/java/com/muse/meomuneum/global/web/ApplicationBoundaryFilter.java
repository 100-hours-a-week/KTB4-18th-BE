package com.muse.meomuneum.global.web;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.response.ApiResponse;

/** Enforces the app boundary before security can create sessions or execute a controller. */
public final class ApplicationBoundaryFilter extends OncePerRequestFilter {
    private final boolean chatApplication;
    private final BooleanSupplier runtimeEnabled;
    private final ObjectMapper mapper;

    public ApplicationBoundaryFilter(boolean chatApplication, BooleanSupplier runtimeEnabled, ObjectMapper mapper) {
        this.chatApplication = chatApplication;
        this.runtimeEnabled = runtimeEnabled;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean chatPath = isChatPath(path);
        boolean management = path.equals("/actuator") || path.startsWith("/actuator/");
        if ((!chatApplication && chatPath) || (chatApplication && !chatPath && !management)) {
            reject(response, 404, "not found");
        } else if (chatApplication && chatPath && !runtimeEnabled.getAsBoolean()) {
            response.setHeader("Retry-After", "5");
            reject(response, 503, "chat service unavailable");
        } else {
            chain.doFilter(request, response);
        }
    }

    private boolean isChatPath(String path) {
        return path.equals("/ws") || path.startsWith("/ws/")
                || path.equals("/api/v1/chat-rooms") || path.startsWith("/api/v1/chat-rooms/")
                || path.matches("/api/v1/regions/[^/]+/chat-room/?");
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), ApiResponse.failure(message));
    }
}
