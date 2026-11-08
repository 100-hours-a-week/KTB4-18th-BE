package com.muse.meomuneum.locationrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.location.provider.RegionCoordinateResolver;
import com.muse.meomuneum.location.provider.ResolvedRegionCode;
import com.muse.meomuneum.locationrecommendation.config.LocationRecommendationConfig;
import com.muse.meomuneum.locationrecommendation.config.LocationRecommendationSettings;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.CancellationRequest;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.CancellationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationRequest;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RegionResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.TrackResponse;
import com.muse.meomuneum.locationrecommendation.exception.LocationRecommendationException;
import com.muse.meomuneum.locationrecommendation.provider.AiLocationRecommendationProvider;
import com.muse.meomuneum.locationrecommendation.provider.AiLocationRecommendationProvider.Result;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository.AdmissionOutcome;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository.CancelOutcome;
import com.muse.meomuneum.locationrecommendation.service.LocationRecommendationService;

import tools.jackson.databind.ObjectMapper;

class LocationRecommendationServiceTest {
    private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID GENERATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final RegionResponse REGION = new RegionResponse(123, "11230", "Dongdaemun-gu", "Seoul");

    private RegionCoordinateResolver resolver;
    private LocationRecommendationRepository repository;
    private AiLocationRecommendationProvider provider;
    private LocationRecommendationService service;
    private ExecutorService contextExecutor;
    private RecommendationRequest request;

    @BeforeEach
    void setUp() {
        resolver = mock(RegionCoordinateResolver.class);
        repository = mock(LocationRecommendationRepository.class);
        provider = mock(AiLocationRecommendationProvider.class);
        contextExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "location-recommendation-context-test");
            thread.setDaemon(true);
            return thread;
        });
        service = new LocationRecommendationService(resolver, repository, provider,
                new LocationRecommendationSettings(URI.create("http://127.0.0.1:9000/v1/location/recommendations"),
                        Duration.ofSeconds(120), Duration.ofSeconds(3), Duration.ofSeconds(130), true),
                contextExecutor);
        request = new RecommendationRequest(37.5744, 127.0395, 50, 120, "SESSION_START", GENERATION, 4);
        when(resolver.resolve(37.5744, 127.0395)).thenReturn(new ResolvedRegionCode("11", "11230"));
        when(repository.findActiveRegion(new ResolvedRegionCode("11", "11230"))).thenReturn(Optional.of(REGION));
        when(repository.tryStartAiAttempt(anyLong(), any(), any(), anyLong(), anyLong())).thenReturn(true);
    }

    @AfterEach
    void stopContextExecutor() {
        contextExecutor.shutdownNow();
    }

    @Test
    void sendsTheCanonicalRequestIdAndCoordinatesAndPersistsOnlyValidatedTracks() throws Exception {
        UUID canonicalRequestId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        String message = "산책에 어울리는 곡이에요.";
        TrackResponse track = new TrackResponse("1450779337", "Ohio", "HYUKOH", null, null, null);
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(true, canonicalRequestId, 4, GENERATION, null));
        when(repository.stillOwnsLease(7L, canonicalRequestId, GENERATION, 4)).thenReturn(true);
        when(provider.recommend(eq(canonicalRequestId), eq(37.5744), eq(127.0395), any(Duration.class), any()))
                .thenReturn(new AiLocationRecommendationProvider.Call(
                        CompletableFuture.completedFuture(new Result(List.of(track), message)),
                        () -> {
                        }));
        RecommendationResponse ready = new RecommendationResponse(canonicalRequestId, "READY", null, REGION,
                List.of(track), message, GENERATION, 4, 600_000);
        when(repository.complete(eq(7L), eq(canonicalRequestId), eq(GENERATION), eq(4L), eq(List.of(track)),
                eq(message), anyLong()))
                .thenReturn(ready);

        RecommendationResponse response = service.recommend(7L, REQUEST_ID, request);

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.tracks()).containsExactly(track);
        assertThat(response.recommendationMessage()).isEqualTo(message);
        verify(resolver).resolve(37.5744, 127.0395);
        verify(provider).recommend(eq(canonicalRequestId), eq(37.5744), eq(127.0395), any(Duration.class), any());
        verify(repository).complete(eq(7L), eq(canonicalRequestId), eq(GENERATION), eq(4L), eq(List.of(track)),
                eq(message), anyLong());
    }

    @Test
    void kakaoFailureIsNormalizedAndPreventsAiOrStateAdmission() {
        when(resolver.resolve(37.5744, 127.0395)).thenThrow(new IllegalStateException("provider response"));

        assertThatThrownBy(() -> service.recommend(7L, REQUEST_ID, request))
                .isInstanceOf(LocationRecommendationException.class)
                .satisfies(exception -> assertThat(((LocationRecommendationException) exception).reason())
                        .isEqualTo("LOCATION_CONTEXT_UNAVAILABLE"));

        verify(provider, never()).recommend(any(UUID.class), any(Double.class), any(Double.class), any(Duration.class),
                any());
        verify(repository, never()).admit(anyLong(), any(), any(), any(), eq(true), anyLong());
    }

    @Test
    void cancellationCancelsOwnerFutureAndLateTracksDoNotComplete() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Result> pending = new CompletableFuture<>();
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(true, REQUEST_ID, 4, GENERATION, null));
        when(repository.stillOwnsLease(7L, REQUEST_ID, GENERATION, 4)).thenReturn(true);
        when(provider.recommend(eq(REQUEST_ID), eq(37.5744), eq(127.0395), any(Duration.class), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    return new AiLocationRecommendationProvider.Call(pending, () -> {
                        cancelled.set(true);
                        pending.cancel(true);
                    });
                });
        RecommendationResponse cancelledResponse = new RecommendationResponse(REQUEST_ID, "CANCELLED", "CANCELLED",
                REGION, List.of(), null, GENERATION, 5, 0);
        when(repository.get(7L, REQUEST_ID)).thenReturn(cancelledResponse);
        when(repository.cancel(eq(7L), any(), eq(REQUEST_ID), eq(GENERATION), eq(4L)))
                .thenReturn(new CancelOutcome(new CancellationResponse("CANCELLED", GENERATION, 5), REQUEST_ID));

        var responseFuture = CompletableFuture.supplyAsync(() -> service.recommend(7L, REQUEST_ID, request));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        service.cancel(7L, UUID.randomUUID(), new CancellationRequest(REQUEST_ID, GENERATION, 4));

        assertThat(responseFuture.get(2, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
        assertThat(cancelled).isTrue();
        verify(repository, never()).complete(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), any(), any(), anyLong());
    }

    @Test
    void strictInputRejectsUnknownKeysAndNonV4IdempotencyKeys() throws Exception {
        var mapper = new ObjectMapper();
        var body = mapper.readTree("""
                {"latitude":37.5,"longitude":127,"accuracyMeters":0,"sampleAgeMs":0,
                "trigger":"RESUME","scopeGeneration":"11111111-1111-4111-8111-111111111111",
                "cancelEpoch":0,"extra":true}
                """);

        assertThatThrownBy(() -> LocationRecommendationDtos.parseRecommendationRequest(body))
                .isInstanceOf(LocationRecommendationException.class);
        assertThatThrownBy(() -> LocationRecommendationDtos.parseIdempotencyKey(
                "11111111-1111-1111-8111-111111111111"))
                .isInstanceOf(LocationRecommendationException.class);

        var pollBody = mapper.readTree("""
                {"latitude":37.5,"longitude":127,"accuracyMeters":0,"sampleAgeMs":0,
                "trigger":"POLL","scopeGeneration":"11111111-1111-4111-8111-111111111111",
                "cancelEpoch":0}
                """);
        assertThatThrownBy(() -> LocationRecommendationDtos.parseRecommendationRequest(pollBody))
                .isInstanceOf(LocationRecommendationException.class);
    }

    @Test
    void terminalAdmissionReturnsWithoutStartingAi() {
        RecommendationResponse terminal = new RecommendationResponse(REQUEST_ID, "SUPERSEDED", "RECORDED_REGION",
                REGION, List.of(), null, GENERATION, 4, 0);
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(false, REQUEST_ID, 4, GENERATION, terminal));

        RecommendationResponse response = service.recommend(7L, REQUEST_ID, request);

        assertThat(response).isEqualTo(terminal);
        verify(provider, never()).recommend(any(UUID.class), any(Double.class), any(Double.class), any(Duration.class),
                any());
    }

    @Test
    void failureCleanupTimeoutDoesNotMaskTheOriginalUpstreamFailure() {
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(true, REQUEST_ID, 4, GENERATION, null));
        when(repository.stillOwnsLease(7L, REQUEST_ID, GENERATION, 4)).thenReturn(true);
        when(provider.recommend(eq(REQUEST_ID), eq(37.5744), eq(127.0395), any(Duration.class), any()))
                .thenReturn(new AiLocationRecommendationProvider.Call(CompletableFuture.failedFuture(
                        new AiLocationRecommendationProvider.AiProviderException(503, "UPSTREAM_UNAVAILABLE",
                                "recommendation service is unavailable")),
                        () -> {
                        }));
        doThrow(new LocationRecommendationException(504, "TIMEOUT", "recommendation request timed out"))
                .when(repository).fail(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), eq("UPSTREAM_UNAVAILABLE"),
                        anyLong());

        assertThatThrownBy(() -> service.recommend(7L, REQUEST_ID, request))
                .isInstanceOfSatisfying(LocationRecommendationException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(503);
                    assertThat(exception.reason()).isEqualTo("UPSTREAM_UNAVAILABLE");
                });
    }

    @Test
    void oversizedSuccessfulAiResponseMapsToInvalidResponseAndDoesNotComplete() {
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(true, REQUEST_ID, 4, GENERATION, null));
        when(repository.stillOwnsLease(7L, REQUEST_ID, GENERATION, 4)).thenReturn(true);
        when(provider.recommend(eq(REQUEST_ID), eq(37.5744), eq(127.0395), any(Duration.class), any()))
                .thenReturn(new AiLocationRecommendationProvider.Call(CompletableFuture.failedFuture(
                        new AiLocationRecommendationProvider.ResponseBodyTooLargeException(200)),
                        () -> {
                        }));

        assertThatThrownBy(() -> service.recommend(7L, REQUEST_ID, request))
                .isInstanceOfSatisfying(LocationRecommendationException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(502);
                    assertThat(exception.reason()).isEqualTo("INVALID_RESPONSE");
                });
        verify(repository).fail(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), eq("INVALID_RESPONSE"), anyLong());
        verify(repository, never()).complete(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), any(), any(), anyLong());
    }

    @Test
    void finalizationTimeoutRemainsTimeoutWhenFailureCleanupAlsoTimesOut() {
        TrackResponse track = new TrackResponse("42", "Song", "Artist", null, null, null);
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(true, REQUEST_ID, 4, GENERATION, null));
        when(repository.stillOwnsLease(7L, REQUEST_ID, GENERATION, 4)).thenReturn(true);
        when(provider.recommend(eq(REQUEST_ID), eq(37.5744), eq(127.0395), any(Duration.class), any()))
                .thenReturn(new AiLocationRecommendationProvider.Call(
                        CompletableFuture.completedFuture(new Result(List.of(track), null)),
                        () -> {
                        }));
        when(repository.complete(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), eq(List.of(track)), eq(null),
                anyLong()))
                .thenThrow(new LocationRecommendationException(504, "TIMEOUT", "recommendation request timed out"));
        doThrow(new LocationRecommendationException(504, "TIMEOUT", "recommendation request timed out"))
                .when(repository).fail(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), eq("TIMEOUT"), anyLong());

        assertThatThrownBy(() -> service.recommend(7L, REQUEST_ID, request))
                .isInstanceOfSatisfying(LocationRecommendationException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(504);
                    assertThat(exception.reason()).isEqualTo("TIMEOUT");
                });
    }

    @Test
    void recommendationTimeoutOverrideIsClampedToOneHundredTwentySeconds() {
        var configuration = new LocationRecommendationConfig();

        var settings = configuration.locationRecommendationSettings("https://ai.example.test",
                "https://ai.example.test", Duration.ofSeconds(3), Duration.ofSeconds(180), true);

        assertThat(settings.aiTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(settings.backendTimeout()).isEqualTo(Duration.ofSeconds(130));
    }

    @Test
    void regionResolutionIsBoundedByTheBackendDeadline() throws Exception {
        CountDownLatch resolverStarted = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        when(resolver.resolve(37.5744, 127.0395)).thenAnswer(invocation -> {
            resolverStarted.countDown();
            releaseResolver.await();
            return new ResolvedRegionCode("11", "11230");
        });
        service = new LocationRecommendationService(resolver, repository, provider,
                new LocationRecommendationSettings(URI.create("http://127.0.0.1:9000/v1/location/recommendations"),
                        Duration.ofSeconds(120), Duration.ofSeconds(3), Duration.ofSeconds(4), true),
                contextExecutor);

        assertThatThrownBy(() -> service.recommend(7L, REQUEST_ID, request))
                .isInstanceOfSatisfying(LocationRecommendationException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(504);
                    assertThat(exception.reason()).isEqualTo("TIMEOUT");
                });
        assertThat(resolverStarted.await(1, TimeUnit.SECONDS)).isTrue();
        verify(repository, never()).admit(anyLong(), any(), any(), any(), eq(true), anyLong());
        verify(provider, never()).recommend(any(UUID.class), any(Double.class), any(Double.class), any(Duration.class),
                any());
        releaseResolver.countDown();
    }

    @Test
    void interruptingAiWaitFailsTheRequestAndDoesNotLeaveItProcessing() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> requestThread = new AtomicReference<>();
        CompletableFuture<Result> pending = new CompletableFuture<>();
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(true, REQUEST_ID, 4, GENERATION, null));
        when(repository.stillOwnsLease(7L, REQUEST_ID, GENERATION, 4)).thenReturn(true);
        when(provider.recommend(eq(REQUEST_ID), eq(37.5744), eq(127.0395), any(Duration.class), any()))
                .thenAnswer(invocation -> {
                    requestThread.set(Thread.currentThread());
                    started.countDown();
                    return new AiLocationRecommendationProvider.Call(pending, () -> pending.cancel(true));
                });
        var requestExecutor = Executors.newSingleThreadExecutor();
        try {
            var response = requestExecutor.submit(() -> service.recommend(7L, REQUEST_ID, request));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            requestThread.get().interrupt();

            assertThatThrownBy(() -> response.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .satisfies(exception -> assertThat(exception.getCause())
                            .isInstanceOfSatisfying(LocationRecommendationException.class, cause -> {
                                assertThat(cause.status()).isEqualTo(503);
                                assertThat(cause.reason()).isEqualTo("UPSTREAM_UNAVAILABLE");
                            }));
            verify(repository).fail(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), eq("UPSTREAM_UNAVAILABLE"),
                    anyLong());
            verify(repository, never()).complete(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), any(), any(),
                    anyLong());
        } finally {
            requestExecutor.shutdownNow();
        }
    }

    @Test
    void cancellationForOneUserCannotCancelAnotherUsersSameUuidRequest() throws Exception {
        RecommendationRequest otherRequest = new RecommendationRequest(37.6, 127.0395, 50, 120, "SESSION_START",
                GENERATION, 4);
        when(resolver.resolve(37.6, 127.0395)).thenReturn(new ResolvedRegionCode("11", "11230"));
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(true, REQUEST_ID, 4, GENERATION, null));
        when(repository.admit(eq(8L), eq(REQUEST_ID), eq(otherRequest), eq(REGION), eq(true), anyLong()))
                .thenReturn(new AdmissionOutcome(true, REQUEST_ID, 4, GENERATION, null));
        when(repository.stillOwnsLease(7L, REQUEST_ID, GENERATION, 4)).thenReturn(true);
        when(repository.stillOwnsLease(8L, REQUEST_ID, GENERATION, 4)).thenReturn(true);

        CountDownLatch bothStarted = new CountDownLatch(2);
        CompletableFuture<Result> userSevenPending = new CompletableFuture<>();
        CompletableFuture<Result> userEightPending = new CompletableFuture<>();
        AtomicBoolean userSevenCancelled = new AtomicBoolean();
        AtomicBoolean userEightCancelled = new AtomicBoolean();
        when(provider.recommend(eq(REQUEST_ID), eq(37.5744), eq(127.0395), any(Duration.class), any()))
                .thenAnswer(invocation -> {
                    bothStarted.countDown();
                    return new AiLocationRecommendationProvider.Call(userSevenPending, () -> {
                        userSevenCancelled.set(true);
                        userSevenPending.cancel(true);
                    });
                });
        when(provider.recommend(eq(REQUEST_ID), eq(37.6), eq(127.0395), any(Duration.class), any()))
                .thenAnswer(invocation -> {
                    bothStarted.countDown();
                    return new AiLocationRecommendationProvider.Call(userEightPending, () -> {
                        userEightCancelled.set(true);
                        userEightPending.cancel(true);
                    });
                });
        when(repository.cancel(eq(7L), any(), eq(REQUEST_ID), eq(GENERATION), eq(4L)))
                .thenReturn(new CancelOutcome(new CancellationResponse("CANCELLED", GENERATION, 5), REQUEST_ID));
        when(repository.get(7L, REQUEST_ID)).thenReturn(new RecommendationResponse(REQUEST_ID, "CANCELLED",
                "CANCELLED", REGION, List.of(), null, GENERATION, 5, 0));
        TrackResponse track = new TrackResponse("42", "Song", "Artist", null, null, null);
        when(repository.complete(eq(8L), eq(REQUEST_ID), eq(GENERATION), eq(4L), eq(List.of(track)), eq(null),
                anyLong()))
                .thenReturn(new RecommendationResponse(REQUEST_ID, "READY", null, REGION, List.of(track), null,
                        GENERATION, 4, 600_000));

        var userSevenResponse = CompletableFuture.supplyAsync(() -> service.recommend(7L, REQUEST_ID, request));
        var userEightResponse = CompletableFuture.supplyAsync(() -> service.recommend(8L, REQUEST_ID, otherRequest));
        assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();

        service.cancel(7L, UUID.randomUUID(), new CancellationRequest(REQUEST_ID, GENERATION, 4));
        assertThat(userSevenResponse.get(1, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
        assertThat(userSevenCancelled).isTrue();
        assertThat(userEightCancelled).isFalse();
        assertThat(userEightPending.isCancelled()).isFalse();

        userEightPending.complete(new Result(List.of(track), null));
        assertThat(userEightResponse.get(1, TimeUnit.SECONDS).status()).isEqualTo("READY");
    }

    @Test
    void exactAccuracyAndSampleAgeBoundariesAreAccepted() throws Exception {
        var mapper = new ObjectMapper();
        var body = mapper.readTree("""
                {"latitude":90,"longitude":180,"accuracyMeters":300,"sampleAgeMs":30000,
                "trigger":"RESUME","scopeGeneration":"11111111-1111-4111-8111-111111111111",
                "cancelEpoch":0}
                """);

        RecommendationRequest parsed = LocationRecommendationDtos.parseRecommendationRequest(body);

        assertThat(parsed.latitude()).isEqualTo(90);
        assertThat(parsed.longitude()).isEqualTo(180);
        assertThat(parsed.accuracyMeters()).isEqualTo(300);
        assertThat(parsed.sampleAgeMs()).isEqualTo(30_000);
    }

    @Test
    void failureCleanupUsesItsOwnBoundedDeadlineInsideBackendHardDeadline() {
        AtomicLong operationDeadline = new AtomicLong();
        AtomicLong cleanupDeadline = new AtomicLong();
        AtomicLong cleanupStartedAt = new AtomicLong();
        when(repository.admit(eq(7L), eq(REQUEST_ID), eq(request), eq(REGION), eq(true), anyLong()))
                .thenAnswer(invocation -> {
                    operationDeadline.set(invocation.getArgument(5));
                    return new AdmissionOutcome(true, REQUEST_ID, 4, GENERATION, null);
                });
        when(repository.stillOwnsLease(7L, REQUEST_ID, GENERATION, 4)).thenReturn(true);
        when(provider.recommend(eq(REQUEST_ID), eq(37.5744), eq(127.0395), any(Duration.class), any()))
                .thenReturn(new AiLocationRecommendationProvider.Call(CompletableFuture.failedFuture(
                        new AiLocationRecommendationProvider.AiProviderException(503, "UPSTREAM_UNAVAILABLE",
                                "recommendation service is unavailable")),
                        () -> {
                        }));
        org.mockito.Mockito.doAnswer(invocation -> {
            cleanupStartedAt.set(System.nanoTime());
            cleanupDeadline.set(invocation.getArgument(5));
            return null;
        }).when(repository).fail(eq(7L), eq(REQUEST_ID), eq(GENERATION), eq(4L), eq("UPSTREAM_UNAVAILABLE"),
                anyLong());

        assertThatThrownBy(() -> service.recommend(7L, REQUEST_ID, request))
                .isInstanceOf(LocationRecommendationException.class);

        long operationEnd = operationDeadline.get();
        long cleanupEnd = cleanupDeadline.get();
        long twoSeconds = Duration.ofSeconds(2).toNanos();
        assertThat(cleanupEnd).isGreaterThan(cleanupStartedAt.get());
        assertThat(cleanupEnd).isLessThanOrEqualTo(operationEnd + twoSeconds);
        assertThat(cleanupEnd - cleanupStartedAt.get()).isLessThanOrEqualTo(twoSeconds);
    }
}
