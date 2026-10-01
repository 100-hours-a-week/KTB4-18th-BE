package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.muse.meomuneum.chat.region.domain.RegionLevel;
import com.muse.meomuneum.chat.region.repository.RegionRepository;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.secure=false", "auth.cookie.secure=false"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MUSIC_RECORD_LOCAL_TESTS", matches = "true")
class SessionCookieScopeHttpIntegrationTest {
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LocationResolutionTokenProvider locations;
    @Autowired
    private RegionRepository regions;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TEST_PASSWORD = "LogoutHttpTest123!";

    @Test
    void csrfSessionCookieIsScopedToAuthAndBearerWritesNeedNoSessionCsrf() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long userId = positiveId();
        long sidoId = positiveId();
        long sigunguId = positiveId();
        long dotId = positiveId();
        long musicId = positiveId();
        jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role) VALUES (?,?,?,?,?)",
                userId, suffix + "@test.local", passwordEncoder.encode(TEST_PASSWORD), "테스트", "USER");
        jdbc.update("INSERT INTO regions (id,code,name,level,is_active) VALUES (?,?,?,?,TRUE)",
                sidoId, "s" + suffix, "서울특별시", "SIDO");
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level,is_active) VALUES (?,?,?,?,?,TRUE)",
                sigunguId, sidoId, "g" + suffix, "성동구", "SIGUNGU");
        jdbc.update("INSERT INTO map_dots (id,code,region_id,latitude,longitude,is_active) "
                + "VALUES (?,?,?,?,?,TRUE)", dotId, "d" + suffix, sigunguId, 37.5, 127.0);
        jdbc.update("INSERT INTO music (id,provider,external_music_id,title,artist_name) VALUES (?,?,?,?,?)",
                musicId, "ITUNES", String.valueOf(musicId), "테스트 노래", "테스트 가수");
        try {
            verifyMusicRecordWritesWithCsrf(userId, sidoId, sigunguId, dotId, musicId, suffix);
        } finally {
            jdbc.update("DELETE FROM music_records WHERE user_id=?", userId);
            jdbc.update("DELETE FROM map_dots WHERE id=?", dotId);
            jdbc.update("DELETE FROM regions WHERE id=?", sigunguId);
            jdbc.update("DELETE FROM regions WHERE id=?", sidoId);
            jdbc.update("DELETE FROM music WHERE id=?", musicId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
    }

    private void verifyMusicRecordWritesWithCsrf(long userId, long sidoId, long sigunguId,
            long dotId, long musicId, String suffix) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        URI base = URI.create("http://127.0.0.1:" + port);
        HttpResponse<String> preLoginCsrf = client.send(HttpRequest.newBuilder(
                base.resolve("/api/v1/auth/token/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(preLoginCsrf.statusCode()).isEqualTo(200);
        Map<String, List<String>> loginCookieHeaders = cookies.get(base.resolve("/api/v1/auth/login"), Map.of());
        String loginCookie = loginCookieHeaders.entrySet().stream()
                .filter(entry -> "Cookie".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
        assertThat(loginCookie.contains("JSESSIONID=")).isTrue();
        String loginBody = "{\"email\":\"" + suffix + "@test.local\",\"password\":\""
                + TEST_PASSWORD + "\"}";
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                .header("Origin", "http://localhost:5174")
                .header("Content-Type", "application/json")
                .header("Cookie", loginCookie)
                .POST(HttpRequest.BodyPublishers.ofString(loginBody)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as("login response: %s", login.body()).isEqualTo(200);
        List<String> loginSetCookies = login.headers().allValues("Set-Cookie");
        assertThat(loginSetCookies).anyMatch(cookie -> cookie.startsWith("refresh_token="));
        assertThat(loginSetCookies).anyMatch(cookie -> cookie.startsWith("JSESSIONID="));
        String loginSessionId = currentSessionId(cookies);
        String bearer = mapper.readTree(login.body()).path("data").path("access_token").asText();
        assertThat(jwtTokenProvider.parseAccessToken(bearer).sessionId()).isEqualTo(loginSessionId);

        String sessionHeader = preLoginCsrf.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(sessionHeader).contains("JSESSIONID=", "Path=/api/v1/auth", "HttpOnly");
        assertThat(sessionHeader.toLowerCase()).contains("samesite=lax").doesNotContain("secure");
        String path = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .map(java.net.HttpCookie::getPath).findFirst().orElseThrow();
        assertThat(path).isEqualTo("/api/v1/auth");

        HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(
                base.resolve("/api/v1/auth/token/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        String csrfSessionId = currentSessionId(cookies);
        assertThat(csrfSessionId).isEqualTo(loginSessionId);
        String token = mapper.readTree(csrf.body()).path("data").path("csrf_token").asText();
        assertThat(bearer).isNotBlank();
        assertThat(cookies.getCookieStore().getCookies().stream()
                .anyMatch(cookie -> "refresh_token".equals(cookie.getName()))).isTrue();
        var sido = regions.findByCodeAndLevelAndActiveTrue("s" + suffix, RegionLevel.SIDO).orElseThrow();
        var sigungu = regions.findByCodeAndLevelAndActiveTrue("g" + suffix, RegionLevel.SIGUNGU).orElseThrow();
        String locationToken = locations.issue(userId, sido, sigungu, dotId).value();
        String body = "{\"music\":{\"provider\":\"ITUNES\",\"external_music_id\":\""
                + musicId + "\"},\"location_resolution_token\":\"" + locationToken + "\"}";

        assertError(post(client, base, body, bearer, null, null), 403, "request rejected");
        HttpResponse<String> rejectedOrigin = post(client, base, body, bearer, null, "https://attacker.example");
        assertThat(rejectedOrigin.statusCode()).isEqualTo(403);
        assertThat(rejectedOrigin.body()).isEqualTo("Invalid CORS request");

        HttpResponse<String> write = client.send(HttpRequest.newBuilder(
                base.resolve("/api/v1/music-records"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:5174")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(write.statusCode()).isEqualTo(201);
        String sessionIdAfterBearerWrite = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .map(java.net.HttpCookie::getValue).findFirst().orElseThrow();
        assertThat(sessionIdAfterBearerWrite.equals(csrfSessionId))
                .as("bearer-authenticated API request must keep the CSRF session")
                .isTrue();
        long recordId = mapper.readTree(write.body()).path("data").path("record_id").asLong();
        assertThat(recordId).isPositive();

        URI record = base.resolve("/api/v1/music-records/" + recordId);
        assertError(patch(client, record, bearer, null, null), 403, "request rejected");
        assertThat(patch(client, record, bearer, null, "http://localhost:5174").statusCode()).isEqualTo(200);
        CookieManager anonymousCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient anonymousClient = HttpClient.newBuilder().cookieHandler(anonymousCookies).build();
        assertError(post(anonymousClient, base, body, null, null, "http://localhost:5174"), 401, "unauthorized");

        HttpResponse<String> rejectedLogout = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/logout"))
                .header("Origin", "http://localhost:5174")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rejectedLogout.statusCode()).isEqualTo(403);
        Map<String, List<String>> logoutCookieHeaders = cookies.get(base.resolve("/api/v1/auth/logout"), Map.of());
        String outgoingCookie = logoutCookieHeaders.entrySet().stream()
                .filter(entry -> "Cookie".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
        assertThat(outgoingCookie).contains("JSESSIONID=");
        HttpResponse<String> logout = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/logout"))
                .header("Origin", "http://localhost:5174")
                .header("Authorization", "Bearer " + bearer)
                .header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).as("logout response: %s", logout.body()).isEqualTo(204);
        String refreshHeader = logout.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(refreshHeader).contains("refresh_token=", "Path=/api/v1/auth", "HttpOnly", "Max-Age=0")
                .doesNotContain("Secure");
        assertThat(refreshHeader.toLowerCase()).contains("samesite=lax");
        HttpResponse<String> postLogoutCsrf = client.send(HttpRequest.newBuilder(
                base.resolve("/api/v1/auth/token/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(postLogoutCsrf.statusCode()).isEqualTo(200);
        assertThat(currentSessionId(cookies)).isNotEqualTo(csrfSessionId);
    }

    private HttpResponse<String> post(HttpClient client, URI base, String body, String bearer, String csrf,
            String origin)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve("/api/v1/music-records"))
                .header("Content-Type", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (csrf != null) {
            request.header("X-CSRF-TOKEN", csrf);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).path("message").asText()).isEqualTo(message);
    }

    private HttpResponse<String> patch(HttpClient client, URI record, String bearer, String csrf, String origin)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(record)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer);
        if (csrf != null) {
            request.header("X-CSRF-TOKEN", csrf);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.method("PATCH", HttpRequest.BodyPublishers.ofString("{\"emotion_memo\":\"수정\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String currentSessionId(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .map(java.net.HttpCookie::getValue).findFirst().orElseThrow();
    }

    private long positiveId() {
        return 1_000_000_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L);
    }
}
