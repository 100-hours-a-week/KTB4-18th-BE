package com.muse.meomuneum.global.security;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

public class RequestOriginValidationFilter extends OncePerRequestFilter {

    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final Set<String> allowedOrigins;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public RequestOriginValidationFilter(String allowedOrigins, SecurityErrorResponseWriter errorResponseWriter) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .map(origin -> normalizeOrigin(origin, false))
                .filter(origin -> origin != null)
                .collect(Collectors.toUnmodifiableSet());
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (STATE_CHANGING_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))
                && !hasAllowedOrigin(request)) {
            errorResponseWriter.write(request, response, SecurityErrorCode.ACCESS_DENIED);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasAllowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            if (origin.isBlank()) {
                return false;
            }
            String normalizedOrigin = normalizeOrigin(origin, false);
            return normalizedOrigin != null && allowedOrigins.contains(normalizedOrigin);
        }
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return false;
        }
        String normalizedReferer = normalizeOrigin(referer, true);
        return normalizedReferer != null && allowedOrigins.contains(normalizedReferer);
    }

    private static String normalizeOrigin(String value, boolean allowPath) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getUserInfo() != null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))) {
                return null;
            }
            if (!allowPath && ((uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null)) {
                return null;
            }
            int port = uri.getPort();
            if (port == ("http".equals(scheme) ? 80 : 443)) {
                port = -1;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return scheme + "://" + normalizedHost + (port < 0 ? "" : ":" + port);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
