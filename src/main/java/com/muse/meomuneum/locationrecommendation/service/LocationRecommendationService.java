package com.muse.meomuneum.locationrecommendation.service;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

import com.muse.meomuneum.location.provider.RegionCoordinateResolver;
import com.muse.meomuneum.location.provider.ResolvedRegionCode;
import com.muse.meomuneum.locationrecommendation.config.LocationRecommendationSettings;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.CancellationRequest;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.CancellationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.ContextResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.ContextTokenRequest;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.DisplayClaimResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationRequest;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RegionResponse;
import com.muse.meomuneum.locationrecommendation.exception.LocationRecommendationException;
import com.muse.meomuneum.locationrecommendation.provider.AiLocationRecommendationProvider;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository.AdmissionOutcome;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository.CancelOutcome;

@Service
public class LocationRecommendationService {
    private static final long FINALIZATION_RESERVE_NANOS = Duration.ofSeconds(2).toNanos();

    private final RegionCoordinateResolver coordinateResolver;
    private final LocationRecommendationRepository repository;
    private final AiLocationRecommendationProvider aiProvider;
    private final LocationRecommendationSettings settings;
    private final ExecutorService contextExecutor;
    private final ConcurrentHashMap<InFlightKey, InFlightOperation> inFlight = new ConcurrentHashMap<>();

    public LocationRecommendationService(RegionCoordinateResolver coordinateResolver,
            LocationRecommendationRepository repository, AiLocationRecommendationProvider aiProvider,
            LocationRecommendationSettings settings,
            @Qualifier("locationRecommendationContextExecutor") ExecutorService contextExecutor) {
        this.coordinateResolver = coordinateResolver;
        this.repository = repository;
        this.aiProvider = aiProvider;
        this.settings = settings;
        this.contextExecutor = contextExecutor;
    }

    public ContextResponse context(long userId) {
        return repository.context(userId);
    }

    public RecommendationResponse recommend(long userId, UUID requestId, RecommendationRequest request) {
        long startedAt = System.nanoTime();
        long hardDeadline = startedAt + settings.backendTimeout().toNanos();
        long operationDeadline = hardDeadline - FINALIZATION_RESERVE_NANOS;
        RegionResponse region = resolveRegionWithinDeadline(request.latitude(), request.longitude(), operationDeadline);
        requireBeforeDeadline(operationDeadline);
        AdmissionOutcome admission;
        try {
            admission = repository.admit(userId, requestId, request, region, settings.enabled(), operationDeadline);
        } catch (RuntimeException exception) {
            if (System.nanoTime() - operationDeadline >= 0 || isDatabaseTimeout(exception)) {
                throw timeout();
            }
            throw exception;
        }
        if (System.nanoTime() - operationDeadline >= 0) {
            if (admission.invokeAi()) {
                bestEffortFail(userId, admission, "TIMEOUT", hardDeadline);
            }
            throw timeout();
        }
        if (!admission.invokeAi()) {
            return admission.response();
        }
        if (remainingAiNanos(operationDeadline) <= 0) {
            bestEffortFail(userId, admission, "TIMEOUT", hardDeadline);
            throw timeout();
        }

        InFlightOperation operation = new InFlightOperation();
        InFlightKey operationKey = new InFlightKey(userId, admission.canonicalRequestId());
        inFlight.put(operationKey, operation);
        try {
            if (!repository.stillOwnsLease(userId, admission.canonicalRequestId(), admission.scopeGeneration(),
                    admission.admittedCancelEpoch())) {
                return repository.get(userId, admission.canonicalRequestId());
            }
            long aiNanos = Math.min(settings.aiTimeout().toNanos(), remainingAiNanos(operationDeadline));
            if (aiNanos <= 0) {
                bestEffortFail(userId, admission, "TIMEOUT", hardDeadline);
                throw timeout();
            }
            Duration aiTimeout = Duration.ofNanos(aiNanos);
            AiLocationRecommendationProvider.Call call = aiProvider.recommend(request.latitude(),
                    request.longitude(), aiTimeout, () -> repository.tryStartAiAttempt(userId,
                            admission.canonicalRequestId(), admission.scopeGeneration(),
                            admission.admittedCancelEpoch(), operationDeadline));
            operation.attach(call);
            if (!call.dispatched()) {
                return repository.get(userId, admission.canonicalRequestId());
            }
            List<com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.TrackResponse> tracks;
            try {
                long waitNanos = Math.min(aiNanos, remainingAiNanos(operationDeadline));
                if (waitNanos <= 0) {
                    operation.cancel();
                    bestEffortFail(userId, admission, "TIMEOUT", hardDeadline);
                    throw timeout();
                }
                tracks = call.result().get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                operation.cancel();
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (TimeoutException exception) {
                operation.cancel();
                bestEffortFail(userId, admission, "TIMEOUT", hardDeadline);
                throw timeout();
            } catch (ExecutionException exception) {
                if (operation.cancelled()) {
                    return repository.get(userId, admission.canonicalRequestId());
                }
                Throwable cause = unwrap(exception);
                if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
                    operation.cancel();
                }
                throw mapProviderFailure(cause);
            } catch (CancellationException exception) {
                if (operation.cancelled()) {
                    return repository.get(userId, admission.canonicalRequestId());
                }
                bestEffortFail(userId, admission, "UPSTREAM_UNAVAILABLE", hardDeadline);
                throw unavailable();
            }
            if (System.nanoTime() - operationDeadline >= 0) {
                operation.cancel();
                bestEffortFail(userId, admission, "TIMEOUT", hardDeadline);
                throw timeout();
            }
            return repository.complete(userId, admission.canonicalRequestId(), admission.scopeGeneration(),
                    admission.admittedCancelEpoch(), tracks, operationDeadline);
        } catch (AiLocationRecommendationProvider.AiProviderException exception) {
            bestEffortFail(userId, admission, exception.reason(), hardDeadline);
            throw new LocationRecommendationException(exception.status(), exception.reason(), publicMessage(exception));
        } catch (LocationRecommendationException exception) {
            bestEffortFail(userId, admission, exception.reason(), hardDeadline);
            throw exception;
        } catch (RuntimeException exception) {
            if (System.nanoTime() - operationDeadline >= 0 || isDatabaseTimeout(exception)) {
                bestEffortFail(userId, admission, "TIMEOUT", hardDeadline);
                throw timeout();
            }
            if (operation.cancelled()) {
                return repository.get(userId, admission.canonicalRequestId());
            }
            bestEffortFail(userId, admission, "UPSTREAM_UNAVAILABLE", hardDeadline);
            throw unavailable();
        } finally {
            inFlight.remove(operationKey, operation);
        }
    }

    public RecommendationResponse get(long userId, UUID requestId) {
        return repository.get(userId, requestId);
    }

    public DisplayClaimResponse claim(long userId, UUID requestId, UUID claimKey, ContextTokenRequest context) {
        return repository.claim(userId, requestId, claimKey, context.scopeGeneration(), context.cancelEpoch());
    }

    public CancellationResponse cancel(long userId, UUID cancellationId, CancellationRequest request) {
        CancelOutcome outcome = repository.cancel(userId, cancellationId, request.requestId(),
                request.scopeGeneration(), request.cancelEpoch());
        if (outcome.ownerRequestId() != null) {
            InFlightOperation operation = inFlight.get(new InFlightKey(userId, outcome.ownerRequestId()));
            if (operation != null) {
                operation.cancel();
            }
        }
        return outcome.response();
    }

    private RegionResponse resolveRegion(double latitude, double longitude) {
        ResolvedRegionCode codes;
        try {
            codes = coordinateResolver.resolve(latitude, longitude);
        } catch (RuntimeException exception) {
            throw unavailableLocation();
        }
        return repository.findActiveRegion(codes).orElseThrow(LocationRecommendationService::unavailableLocation);
    }

    private RegionResponse resolveRegionWithinDeadline(double latitude, double longitude, long deadline) {
        Future<RegionResponse> resolution;
        try {
            resolution = contextExecutor.submit(() -> resolveRegion(latitude, longitude));
        } catch (RejectedExecutionException exception) {
            throw unavailableLocation();
        }
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            resolution.cancel(true);
            throw timeout();
        }
        try {
            return resolution.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            resolution.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailableLocation();
        } catch (TimeoutException exception) {
            resolution.cancel(true);
            throw timeout();
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof LocationRecommendationException locationException) {
                throw locationException;
            }
            throw unavailableLocation();
        }
    }

    private static long remainingAiNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private static void requireBeforeDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) {
            throw timeout();
        }
    }

    private static Throwable unwrap(Throwable exception) {
        Throwable result = exception;
        while ((result instanceof ExecutionException || result instanceof java.util.concurrent.CompletionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static boolean isDatabaseTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof QueryTimeoutException || current instanceof TransactionTimedOutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void bestEffortFail(long userId, AdmissionOutcome admission, String reason, long hardDeadlineNanos) {
        boolean interrupted = Thread.interrupted();
        try {
            long cleanupDeadline = Math.min(hardDeadlineNanos,
                    System.nanoTime() + FINALIZATION_RESERVE_NANOS);
            if (System.nanoTime() - cleanupDeadline >= 0) {
                return;
            }
            repository.fail(userId, admission.canonicalRequestId(), admission.scopeGeneration(),
                    admission.admittedCancelEpoch(), reason, cleanupDeadline);
        } catch (RuntimeException ignored) {
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RuntimeException mapProviderFailure(Throwable failure) {
        if (failure instanceof AiLocationRecommendationProvider.AiProviderException exception) {
            return new LocationRecommendationException(exception.status(), exception.reason(),
                    publicMessage(exception));
        }
        if (failure instanceof AiLocationRecommendationProvider.ResponseBodyTooLargeException exception) {
            return exception.statusCode() >= 500
                    ? unavailable()
                    : new LocationRecommendationException(502, "INVALID_RESPONSE",
                            "recommendation response is invalid");
        }
        if (failure instanceof HttpTimeoutException || failure instanceof TimeoutException) {
            return timeout();
        }
        if (failure instanceof java.io.IOException) {
            return unavailable();
        }
        return new LocationRecommendationException(502, "INVALID_RESPONSE", "recommendation response is invalid");
    }

    private static String publicMessage(AiLocationRecommendationProvider.AiProviderException exception) {
        return exception.status() == 503
                ? "recommendation service is unavailable"
                : "recommendation response is invalid";
    }

    private static LocationRecommendationException unavailableLocation() {
        return new LocationRecommendationException(503, "LOCATION_CONTEXT_UNAVAILABLE",
                "location context is unavailable");
    }

    private static LocationRecommendationException timeout() {
        return new LocationRecommendationException(504, "TIMEOUT", "recommendation request timed out");
    }

    private static LocationRecommendationException unavailable() {
        return new LocationRecommendationException(503, "UPSTREAM_UNAVAILABLE",
                "recommendation service is unavailable");
    }

    private static final class InFlightOperation {
        private AiLocationRecommendationProvider.Call call;
        private boolean cancelled;

        private synchronized void attach(AiLocationRecommendationProvider.Call value) {
            call = value;
            if (cancelled) {
                value.cancel().run();
            }
        }

        private synchronized void cancel() {
            cancelled = true;
            if (call != null) {
                call.cancel().run();
            }
        }

        private synchronized boolean cancelled() {
            return cancelled;
        }
    }

    private record InFlightKey(long userId, UUID requestId) {
    }
}
