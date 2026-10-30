package com.muse.meomuneum.auth.application;

import java.util.Arrays;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

final class QueuedLoginRequestSupport {
    String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }
        String browser = userAgent.contains("Edg/")
                ? "Edge"
                : userAgent.contains("Firefox/")
                        ? "Firefox"
                        : userAgent.contains("Chrome/")
                                ? "Chrome"
                                : userAgent.contains("Safari/") ? "Safari" : "Other browser";
        String platform = userAgent.contains("Android")
                ? "Android"
                : userAgent.contains("iPhone") || userAgent.contains("iPad")
                        ? "iOS"
                        : userAgent.contains("Windows")
                                ? "Windows"
                                : userAgent.contains("Mac OS")
                                        ? "macOS"
                                        : userAgent.contains("Linux") ? "Linux" : "device";
        return browser + " on " + platform;
    }

    Optional<String> getOptionalCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> name.equals(cookie.getName()))
                .map(cookie -> cookie.getValue()).filter(value -> !value.isBlank()).findFirst();
    }
}
