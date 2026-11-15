package com.muse.meomuneum.locationrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.locationrecommendation.config.LocationRecommendationSettings;
import com.muse.meomuneum.locationrecommendation.provider.MockLocationRecommendationProvider;

import tools.jackson.databind.ObjectMapper;

class MockLocationRecommendationProviderTest {
    private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void returnsExactlyFiveTracksForAnApprovedLocalMockAttempt() throws Exception {
        var settings = new LocationRecommendationSettings(
                URI.create("http://ai-server:8000/v1/location/recommendations"),
                Duration.ofSeconds(120), Duration.ofSeconds(3), Duration.ofSeconds(130), true);
        try (ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            MockLocationRecommendationProvider provider = new MockLocationRecommendationProvider(new ObjectMapper(),
                    HttpClient.newBuilder().executor(executor).build(), settings);

            var call = provider.recommend(REQUEST_ID, 37.5, 127.0, Duration.ofSeconds(2), () -> true);

            assertThat(call.dispatched()).isTrue();
            assertThat(call.result().get().tracks()).hasSize(5).allSatisfy(track -> {
                assertThat(track.trackId()).isNotBlank();
                assertThat(track.previewUrl()).startsWith("https://audio-ssl.itunes.apple.com/");
                assertThat(track.artworkUrl()).startsWith("https://");
                assertThat(track.storeUrl()).startsWith("https://");
            });
        }
    }

    @Test
    void doesNotReturnTracksWhenTheAttemptGuardRejectsDispatch() throws Exception {
        var settings = new LocationRecommendationSettings(
                URI.create("http://ai-server:8000/v1/location/recommendations"),
                Duration.ofSeconds(120), Duration.ofSeconds(3), Duration.ofSeconds(130), true);
        try (ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            MockLocationRecommendationProvider provider = new MockLocationRecommendationProvider(new ObjectMapper(),
                    HttpClient.newBuilder().executor(executor).build(), settings);

            var call = provider.recommend(REQUEST_ID, 37.5, 127.0, Duration.ofSeconds(2), () -> false);

            assertThat(call.dispatched()).isFalse();
            assertThat(call.result().get().tracks()).isEmpty();
        }
    }
}
