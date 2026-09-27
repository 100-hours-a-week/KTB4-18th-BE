package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "music-record-local"})
@EnabledIfEnvironmentVariable(named = "MUSIC_RECORD_LOCAL_TESTS", matches = "true")
class SessionCookieSecureHttpIntegrationTest {
    @Value("${local.server.port}")
    private int port;

    @Test
    void defaultSessionCookieIsSecureAndAvailableToMusicRecordPath() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/v1/auth/token/csrf");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("JSESSIONID=", "Path=/", "HttpOnly", "Secure");
        assertThat(cookie.toLowerCase()).contains("samesite=lax");
    }
}
