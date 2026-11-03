package com.muse.meomuneum.locationrecommendation.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.locationrecommendation.config.LocationRecommendationSettings;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.TrackResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("!local-mock")
public class AiLocationRecommendationProvider {
    private static final int MAX_BODY_BYTES = 262_144;
    private static final int MAX_TRACKS = 5;
    private static final Set<String> TRACK_FIELDS = Set.of("track_id", "title", "artist", "preview_url",
            "artwork_url", "store_url");
    private static final Semaphore CONCURRENCY = new Semaphore(16);
    private static final Set<String> ALLOWED_REASONS = Set.of("LOCATION_CONTEXT_UNAVAILABLE", "MODEL_UNAVAILABLE",
            "MODEL_RESPONSE_INVALID", "MUSIC_CATALOG_UNAVAILABLE", "LOCATION_TIMEOUT");

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final LocationRecommendationSettings settings;

    public AiLocationRecommendationProvider(ObjectMapper mapper,
            @Qualifier("locationRecommendationHttpClient") HttpClient client,
            LocationRecommendationSettings settings) {
        this.mapper = mapper;
        this.client = client;
        this.settings = settings;
    }

    public Call recommend(double latitude, double longitude, Duration timeout, BooleanSupplier beforeSend) {
        if (!CONCURRENCY.tryAcquire()) {
            throw new AiProviderException(503, "UPSTREAM_UNAVAILABLE", "recommendation service is busy");
        }
        boolean released = false;
        try {
            byte[] body = mapper.writeValueAsBytes(new AiLocationRequest(latitude, longitude));
            HttpRequest request = HttpRequest.newBuilder(settings.endpoint())
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            if (!beforeSend.getAsBoolean()) {
                CONCURRENCY.release();
                released = true;
                return new Call(CompletableFuture.completedFuture(List.of()), () -> {
                }, false);
            }
            CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request,
                    responseInfo -> new LimitedBodySubscriber(MAX_BODY_BYTES, responseInfo.statusCode()));
            CompletableFuture<List<TrackResponse>> result = exchange.thenApply(this::parseResponse);
            result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
            result.whenComplete((ignored, failure) -> {
                CONCURRENCY.release();
                if (failure instanceof java.util.concurrent.TimeoutException) {
                    exchange.cancel(true);
                }
            });
            return new Call(result, () -> {
                result.cancel(true);
                exchange.cancel(true);
            }, true);
        } catch (RuntimeException exception) {
            if (!released) {
                CONCURRENCY.release();
            }
            throw exception;
        }
    }

    public Duration configuredTimeout() {
        return settings.aiTimeout();
    }

    private List<TrackResponse> parseResponse(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 422) {
            throw new AiProviderException(502, "INVALID_RESPONSE", "recommendation response was invalid");
        }
        if (status == 503) {
            throw new AiProviderException(503, parseUpstreamReason(response.body()),
                    "recommendation service is unavailable");
        }
        if (status >= 500) {
            throw new AiProviderException(503, "UPSTREAM_UNAVAILABLE", "recommendation service is unavailable");
        }
        if (status != 200) {
            throw new AiProviderException(502, "INVALID_RESPONSE", "recommendation response was invalid");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String mediaType = contentType.split(";", 2)[0].trim();
        if (!"application/json".equals(mediaType.toLowerCase(Locale.ROOT))) {
            throw invalidResponse();
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode tracks = root == null ? null : root.get("tracks");
            if (root == null || !root.isObject() || root.size() != 1 || tracks == null || !tracks.isArray()
                    || tracks.size() > MAX_TRACKS) {
                throw invalidResponse();
            }
            List<TrackResponse> result = new ArrayList<>(tracks.size());
            for (JsonNode track : tracks) {
                result.add(parseTrack(track));
            }
            return List.copyOf(result);
        } catch (JacksonException exception) {
            throw invalidResponse();
        }
    }

    private TrackResponse parseTrack(JsonNode track) {
        if (track == null || !track.isObject() || !hasExactTrackFields(track)) {
            throw invalidResponse();
        }
        String trackId = text(track, "track_id");
        String title = text(track, "title");
        String artist = text(track, "artist");
        String preview = safeUrl(track, "preview_url");
        String artwork = safeUrl(track, "artwork_url");
        String store = safeUrl(track, "store_url");
        if (trackId.length() > 200 || title.length() > 200 || artist.length() > 200) {
            throw invalidResponse();
        }
        return new TrackResponse(trackId, title, artist, preview, artwork, store);
    }

    private boolean hasExactTrackFields(JsonNode track) {
        if (track.size() != TRACK_FIELDS.size()) {
            return false;
        }
        for (String field : track.propertyNames()) {
            if (!TRACK_FIELDS.contains(field)) {
                return false;
            }
        }
        return true;
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString()) {
            throw invalidResponse();
        }
        String result = value.stringValue();
        if (result.isBlank()) {
            throw invalidResponse();
        }
        return result;
    }

    private String safeUrl(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalidResponse();
        }
        String url = value.stringValue();
        try {
            var uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65_535
                    || url.length() > 2048) {
                throw invalidResponse();
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
    }

    private String parseUpstreamReason(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode details = root == null ? null : root.get("details");
            JsonNode reasonNode = details == null ? null : details.get("reason");
            if (reasonNode == null && root != null) {
                reasonNode = root.get("reason");
            }
            if (reasonNode == null && root != null && root.get("error") != null) {
                reasonNode = root.get("error").get("reason");
            }
            String reason = reasonNode != null && reasonNode.isString()
                    ? reasonNode.stringValue().toUpperCase(Locale.ROOT)
                    : "UPSTREAM_UNAVAILABLE";
            return ALLOWED_REASONS.contains(reason) ? reason : "UPSTREAM_UNAVAILABLE";
        } catch (JacksonException exception) {
            return "UPSTREAM_UNAVAILABLE";
        }
    }

    private AiProviderException invalidResponse() {
        return new AiProviderException(502, "INVALID_RESPONSE", "recommendation response was invalid");
    }

    public record Call(CompletableFuture<List<TrackResponse>> result, Runnable cancel, boolean dispatched) {
        public Call(CompletableFuture<List<TrackResponse>> result, Runnable cancel) {
            this(result, cancel, true);
        }
    }

    public static class AiProviderException extends RuntimeException {
        private final int status;
        private final String reason;

        public AiProviderException(int status, String reason, String message) {
            super(message);
            this.status = status;
            this.reason = reason;
        }

        public int status() {
            return status;
        }

        public String reason() {
            return reason;
        }
    }

    public static final class ResponseBodyTooLargeException extends IOException {
        private final int statusCode;

        public ResponseBodyTooLargeException(int statusCode) {
            super("recommendation response body is too large");
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    private record AiLocationRequest(double latitude, double longitude) {
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final int statusCode;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int limit, int statusCode) {
            this.limit = limit;
            this.statusCode = statusCode;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer bufferPart : buffers) {
                    int size = bufferPart.remaining();
                    if ((long) buffer.size() + size > limit) {
                        subscription.cancel();
                        body.completeExceptionally(new ResponseBodyTooLargeException(statusCode));
                        return;
                    }
                    byte[] chunk = new byte[size];
                    bufferPart.get(chunk);
                    buffer.write(chunk);
                }
                subscription.request(1);
            } catch (IOException exception) {
                body.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(buffer.toByteArray());
        }
    }
}
