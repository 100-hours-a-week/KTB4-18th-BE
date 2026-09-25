package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.servlet.session.cookie.secure=false")
@ActiveProfiles({"test", "music-record-local"})
@EnabledIfEnvironmentVariable(named = "MUSIC_RECORD_LOCAL_TESTS", matches = "true")
class SessionCookieScopeHttpIntegrationTest {
    @Value("${local.server.port}")
    private int port;

    @Test
    void csrfSessionCookieIsSentToMusicRecordWritePath() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        URI base = URI.create("http://127.0.0.1:" + port);
        HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(
                base.resolve("/api/v1/auth/token/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        String path = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .map(java.net.HttpCookie::getPath).findFirst().orElseThrow();
        assertThat(path).isEqualTo("/");
        String token = new ObjectMapper().readTree(csrf.body()).path("data").path("csrf_token").asText();

        HttpResponse<String> write = client.send(HttpRequest.newBuilder(
                base.resolve("/api/v1/music-records"))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(write.statusCode()).isEqualTo(401);
    }
}
