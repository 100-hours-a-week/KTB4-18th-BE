package com.muse.meomuneum.global.security;

import jakarta.servlet.http.HttpServletRequest;

final class AuthRequestPath {
    private AuthRequestPath() {
    }

    static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }
}
