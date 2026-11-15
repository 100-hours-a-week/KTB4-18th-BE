package com.muse.meomuneum.locationrecommendation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

class LocationRecommendationConfigTest {
    private final LocationRecommendationConfig configuration = new LocationRecommendationConfig();

    @Test
    void acceptsAnAllowlistedHttpsOriginAndBuildsTheDedicatedPath() {
        LocationRecommendationSettings settings = settings("https://ai.example.test:8443",
                "https://ai.example.test:8443");

        assertThat(settings.endpoint())
                .isEqualTo(URI.create("https://ai.example.test:8443/v1/location/recommendations"));
        assertThat(settings.aiTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(settings.backendTimeout()).isEqualTo(Duration.ofSeconds(130));
    }

    @Test
    void rejectsAnOriginThatIsNotExactlyAllowlisted() {
        assertThatThrownBy(() -> settings("https://ai.example.test:8444", "https://ai.example.test:8443"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings("https://other.example.test", "https://ai.example.test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsOriginsWithUserInfoQueryFragmentPathOrIpLiteral() {
        for (String origin : java.util.List.of("https://user@ai.example.test", "https://ai.example.test?x=1",
                "https://ai.example.test#fragment", "https://ai.example.test/api", "https://127.0.0.1:8443")) {
            assertThatThrownBy(() -> settings(origin, origin)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void permitsHttpOnlyForExplicitInternalDockerOrigins() {
        assertThat(settings("http://ai-server:8000", "http://ai-server:8000").endpoint())
                .isEqualTo(URI.create("http://ai-server:8000/v1/location/recommendations"));
        assertThatThrownBy(() -> settings("http://public.example.test", "http://public.example.test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void httpClientDoesNotFollowRedirects() {
        LocationRecommendationSettings settings = settings("https://ai.example.test", "https://ai.example.test");
        ExecutorService executor = configuration.locationRecommendationHttpExecutor();
        try {
            HttpClient client = configuration.locationRecommendationHttpClient(settings, executor);

            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        } finally {
            executor.shutdownNow();
        }
    }

    private LocationRecommendationSettings settings(String baseUrl, String allowlist) {
        return configuration.locationRecommendationSettings(baseUrl, allowlist, Duration.ofSeconds(3),
                Duration.ofSeconds(180), true);
    }
}
