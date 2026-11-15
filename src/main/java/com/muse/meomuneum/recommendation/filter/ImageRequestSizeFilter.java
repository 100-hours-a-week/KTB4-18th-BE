package com.muse.meomuneum.recommendation.filter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ImageRequestSizeFilter extends OncePerRequestFilter {
    public static final long MAX_REQUEST_BYTES = 6L * 1024 * 1024;
    private static final String IMAGE_PATH = "/api/v1/recommendations/images";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && IMAGE_PATH.equals(request.getRequestURI())
                && request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"image request too large\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
