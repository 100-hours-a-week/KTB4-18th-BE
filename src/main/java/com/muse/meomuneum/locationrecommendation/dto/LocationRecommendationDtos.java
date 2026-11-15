package com.muse.meomuneum.locationrecommendation.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.muse.meomuneum.locationrecommendation.exception.LocationRecommendationException;

import tools.jackson.databind.JsonNode;

public final class LocationRecommendationDtos {
    private LocationRecommendationDtos() {
    }

    public record RecommendationRequest(double latitude, double longitude, double accuracyMeters,
            long sampleAgeMs, String trigger, UUID scopeGeneration, long cancelEpoch) {
    }

    public record ContextTokenRequest(UUID scopeGeneration, long cancelEpoch) {
    }

    public record CancellationRequest(UUID requestId, UUID scopeGeneration, long cancelEpoch) {
    }

    public record ContextResponse(UUID scopeGeneration, long cancelEpoch, String kstDate, UUID activeRequestId) {
    }

    public record RegionResponse(long regionId, String code, String name, String sidoName) {
    }

    public record TrackResponse(String trackId, String title, String artist, String previewUrl,
            String artworkUrl, String storeUrl) {
    }

    public record RecommendationResponse(UUID requestId, String status, String reason, RegionResponse region,
            List<TrackResponse> tracks, String recommendationMessage, UUID scopeGeneration, long cancelEpoch,
            long remainingTtlMs) {
        public RecommendationResponse {
            tracks = List.copyOf(tracks);
        }
    }

    public record DisplayClaimResponse(UUID requestId, boolean displayAllowed, String reason) {
    }

    public record CancellationResponse(String status, UUID scopeGeneration, long cancelEpoch) {
    }

    public static RecommendationRequest parseRecommendationRequest(JsonNode node) {
        requireFields(node, Set.of("latitude", "longitude", "accuracyMeters", "sampleAgeMs", "trigger",
                "scopeGeneration", "cancelEpoch"));
        double latitude = finiteNumber(node, "latitude");
        double longitude = finiteNumber(node, "longitude");
        double accuracy = finiteNumber(node, "accuracyMeters");
        long sampleAge = nonnegativeInteger(node, "sampleAgeMs");
        long epoch = nonnegativeInteger(node, "cancelEpoch");
        String trigger = requiredString(node, "trigger");
        UUID generation = parseUuid(requiredString(node, "scopeGeneration"), false);
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                || accuracy < 0 || accuracy > 300 || sampleAge > 30_000
                || !("SESSION_START".equals(trigger) || "RESUME".equals(trigger))) {
            throw invalidRequest();
        }
        return new RecommendationRequest(latitude, longitude, accuracy, sampleAge, trigger, generation, epoch);
    }

    public static ContextTokenRequest parseContextTokenRequest(JsonNode node) {
        requireFields(node, Set.of("scopeGeneration", "cancelEpoch"));
        return new ContextTokenRequest(parseUuid(requiredString(node, "scopeGeneration"), false),
                nonnegativeInteger(node, "cancelEpoch"));
    }

    public static CancellationRequest parseCancellationRequest(JsonNode node) {
        requireFields(node, Set.of("requestId", "scopeGeneration", "cancelEpoch"));
        return new CancellationRequest(parseUuid(requiredString(node, "requestId"), false),
                parseUuid(requiredString(node, "scopeGeneration"), false),
                nonnegativeInteger(node, "cancelEpoch"));
    }

    public static UUID parseIdempotencyKey(String value) {
        UUID id = parseUuid(value, true);
        return id;
    }

    public static UUID parseRequestId(String value) {
        return parseUuid(value, false);
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw invalidRequest();
        }
        Set<String> actual = new HashSet<>();
        actual.addAll(node.propertyNames());
        if (!actual.equals(expected)) {
            throw invalidRequest();
        }
    }

    private static double finiteNumber(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isNumber()) {
            throw invalidRequest();
        }
        double number = value.doubleValue();
        if (!Double.isFinite(number)) {
            throw invalidRequest();
        }
        return number;
    }

    private static long nonnegativeInteger(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalidRequest();
        }
        long number = value.longValue();
        if (number < 0) {
            throw invalidRequest();
        }
        return number;
    }

    private static String requiredString(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalidRequest();
        }
        return value.stringValue();
    }

    private static UUID parseUuid(String value, boolean requireVersionFour) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value) || id.variant() != 2
                    || (requireVersionFour && id.version() != 4)) {
                throw invalidRequest();
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private static LocationRecommendationException invalidRequest() {
        return new LocationRecommendationException(400, "INVALID_REQUEST", "invalid request");
    }
}
