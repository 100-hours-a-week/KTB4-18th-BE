package com.muse.meomuneum.auth.queue;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.config.AuthStateProperties;

@Component
public class AuthQueueCookieFactory {

    private final AuthStateProperties properties;
    private final boolean secure;
    private final String sameSite;

    public AuthQueueCookieFactory(AuthStateProperties properties,
            @Value("${auth.cookie.secure:true}") boolean secure,
            @Value("${auth.cookie.same-site:Lax}") String sameSite) {
        this.properties = properties;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public void set(HttpHeaders headers, String value, long maxAgeSeconds) {
        headers.add(HttpHeaders.SET_COOKIE, ResponseCookie.from(properties.queueCookieName(), value)
                .httpOnly(true).secure(secure).sameSite(sameSite).path("/api/v1/auth")
                .maxAge(Duration.ofSeconds(maxAgeSeconds)).build().toString());
    }
}
