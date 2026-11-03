package com.muse.meomuneum.global.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.springframework.web.filter.OncePerRequestFilter;

public class DirectAuthProtocolMarkerFilter extends OncePerRequestFilter {
    private final SecurityErrorResponseWriter writer;

    public DirectAuthProtocolMarkerFilter(SecurityErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = AuthRequestPath.path(request);
        if (!path.startsWith("/api/v1/auth/")) {
            chain.doFilter(request, response);
            return;
        }
        List<String> markers = Collections.list(request.getHeaders("X-Auth-Login-Protocol"));
        boolean directOnly = path.equals("/api/v1/auth/flows") || path.equals("/api/v1/auth/flow/current-operation")
                || path.equals("/api/v1/auth/flow/rebootstrap") || path.startsWith("/api/v1/auth/login/operations/")
                || path.startsWith("/api/v1/auth/token/refresh/operations/");
        if (markers.isEmpty() && directOnly || !markers.isEmpty()
                && (markers.size() != 1 || !"direct-v2".equals(markers.getFirst())
                        || path.equals("/api/v1/auth/login/recovery"))) {
            writer.write(request, response, SecurityErrorCode.INVALID_LOGIN_PROTOCOL);
            return;
        }
        if (markers.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        var buffered = new CredentialHeaders(response);
        chain.doFilter(request, buffered);
        buffered.finish(response);
    }

    private static final class CredentialHeaders extends HttpServletResponseWrapper {
        private final List<String> cookies = new ArrayList<>();
        private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        private java.io.PrintWriter writer;

        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() {
            return new jakarta.servlet.ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(jakarta.servlet.WriteListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void write(int value) {
                    body.write(value);
                }
            };
        }

        @Override
        public java.io.PrintWriter getWriter() {
            if (writer == null) {
                writer = new java.io.PrintWriter(body, false, java.nio.charset.StandardCharsets.UTF_8);
            }
            return writer;
        }

        @Override
        public void flushBuffer() {
            if (writer != null) {
                writer.flush();
            }
        }

        private void finish(HttpServletResponse response) throws IOException {
            flushBuffer();
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                cookies.forEach(cookie -> response.addHeader("Set-Cookie", cookie));
            }
            body.writeTo(response.getOutputStream());
        }

        private CredentialHeaders(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void addCookie(jakarta.servlet.http.Cookie cookie) {
            var builder = org.springframework.http.ResponseCookie.from(cookie.getName(), cookie.getValue())
                    .httpOnly(cookie.isHttpOnly()).secure(cookie.getSecure());
            if (cookie.getPath() != null) {
                builder.path(cookie.getPath());
            }
            if (cookie.getDomain() != null) {
                builder.domain(cookie.getDomain());
            }
            if (cookie.getMaxAge() >= 0) {
                builder.maxAge(cookie.getMaxAge());
            }
            if (cookie.getAttribute("SameSite") != null) {
                builder.sameSite(cookie.getAttribute("SameSite"));
            }
            cookies.add(builder.build().toString());
        }

        @Override
        public void addHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name)) {
                cookies.add(value);
            } else {
                super.addHeader(name, value);
            }
        }

        @Override
        public void setHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name)) {
                cookies.clear();
                cookies.add(value);
            } else {
                super.setHeader(name, value);
            }
        }
    }
}
