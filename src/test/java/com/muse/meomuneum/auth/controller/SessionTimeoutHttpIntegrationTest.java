package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SessionTimeoutHttpIntegrationTest.SessionProbeConfiguration.class)
class SessionTimeoutHttpIntegrationTest {
    private static final String PROBE_PATH = "/api/v1/auth/session-timeout-probe";

    @Autowired
    private ServerProperties serverProperties;

    @Autowired
    private ServletContext servletContext;

    @Value("${local.server.port}")
    private int port;

    @Test
    void appliesFifteenDayTimeoutAndReusesSessionFromJsessionIdCookie() throws Exception {
        assertThat(serverProperties.getServlet().getSession().getTimeout())
                .isEqualTo(Duration.ofDays(15));
        assertThat(servletContext.getSessionTimeout()).isEqualTo((int) Duration.ofDays(15).toMinutes());

        HttpClient client = HttpClient.newHttpClient();
        URI baseUri = URI.create("http://127.0.0.1:" + port + PROBE_PATH);
        String marker = UUID.randomUUID().toString();
        HttpResponse<String> firstResponse = client.send(HttpRequest.newBuilder(
                baseUri.resolve(PROBE_PATH + "/store?marker=" + marker)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(firstResponse.statusCode()).isEqualTo(200);
        String setCookie = firstResponse.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).contains("JSESSIONID=", "Path=/", "HttpOnly", "Secure");
        assertThat(setCookie.toLowerCase()).contains("samesite=lax");
        String sessionId = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        assertThat(firstResponse.body()).isEqualTo(marker + ":" + sessionId);

        HttpResponse<String> sameSessionResponse = client.send(HttpRequest.newBuilder(
                baseUri.resolve(PROBE_PATH + "/read"))
                .header("Cookie", "JSESSIONID=" + sessionId)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(sameSessionResponse.statusCode()).isEqualTo(200);
        assertThat(sameSessionResponse.body()).isEqualTo(marker + ":" + sessionId);

        HttpResponse<String> noCookieResponse = client.send(HttpRequest.newBuilder(
                baseUri.resolve(PROBE_PATH + "/read")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noCookieResponse.statusCode()).isEqualTo(200);
        assertThat(noCookieResponse.body()).isEqualTo("missing");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionProbeConfiguration {
        @Bean
        SessionProbeController sessionProbeController() {
            return new SessionProbeController();
        }
    }

    @RestController
    static class SessionProbeController {
        private static final String SESSION_MARKER_ATTRIBUTE = "session-timeout-probe-marker";

        @GetMapping(value = PROBE_PATH + "/store", produces = MediaType.TEXT_PLAIN_VALUE)
        String storeMarker(@RequestParam String marker, HttpServletRequest request) {
            var session = request.getSession();
            session.setAttribute(SESSION_MARKER_ATTRIBUTE, marker);
            return marker + ":" + session.getId();
        }

        @GetMapping(value = PROBE_PATH + "/read", produces = MediaType.TEXT_PLAIN_VALUE)
        String readMarker(HttpServletRequest request) {
            var session = request.getSession(false);
            if (session == null) {
                return "missing";
            }
            Object marker = session.getAttribute(SESSION_MARKER_ATTRIBUTE);
            if (!(marker instanceof String)) {
                return "missing";
            }
            return marker + ":" + session.getId();
        }
    }
}
