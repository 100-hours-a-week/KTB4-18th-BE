package com.muse.meomuneum.locationrecommendation.controller;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.CancellationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.ContextResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.DisplayClaimResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationResponse;
import com.muse.meomuneum.locationrecommendation.exception.LocationRecommendationException;
import com.muse.meomuneum.locationrecommendation.service.LocationRecommendationService;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
public class LocationRecommendationController {
    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024;

    private final LocationRecommendationService service;
    private final CurrentUserResolver users;
    private final ObjectMapper mapper;

    @Autowired
    public LocationRecommendationController(LocationRecommendationService service, CurrentUserResolver users,
            ObjectMapper mapper) {
        this.service = service;
        this.users = users;
        this.mapper = mapper;
    }

    public LocationRecommendationController(LocationRecommendationService service, CurrentUserResolver users) {
        this(service, users, new ObjectMapper());
    }

    @GetMapping("/location-recommendation-context")
    public ResponseEntity<ApiResponse<ContextResponse>> context(Authentication authentication) {
        return noStore(ApiResponse.of("recommendation context", service.context(users.resolve(authentication))));
    }

    @PostMapping("/location-recommendations")
    public ResponseEntity<ApiResponse<RecommendationResponse>> recommend(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            HttpServletRequest request,
            Authentication authentication) {
        requireJson(contentType);
        UUID requestId = LocationRecommendationDtos.parseIdempotencyKey(idempotencyKey);
        var recommendationRequest = LocationRecommendationDtos.parseRecommendationRequest(parseBody(request));
        RecommendationResponse response = service.recommend(users.resolve(authentication), requestId,
                recommendationRequest);
        return noStore(ApiResponse.of("location recommendation checked", response));
    }

    @GetMapping("/location-recommendations/{requestId}")
    public ResponseEntity<ApiResponse<RecommendationResponse>> get(
            @PathVariable String requestId, Authentication authentication) {
        UUID id = LocationRecommendationDtos.parseRequestId(requestId);
        return noStore(ApiResponse.of("location recommendation retrieved",
                service.get(users.resolve(authentication), id)));
    }

    @PostMapping("/location-recommendations/{requestId}/display-claims")
    public ResponseEntity<ApiResponse<DisplayClaimResponse>> claim(
            @PathVariable String requestId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            HttpServletRequest request, Authentication authentication) {
        requireJson(contentType);
        UUID id = LocationRecommendationDtos.parseRequestId(requestId);
        UUID claimKey = LocationRecommendationDtos.parseIdempotencyKey(idempotencyKey);
        var context = LocationRecommendationDtos.parseContextTokenRequest(parseBody(request));
        return noStore(ApiResponse.of("location recommendation display claim",
                service.claim(users.resolve(authentication), id, claimKey, context)));
    }

    @PostMapping("/location-recommendation-context/cancellations")
    public ResponseEntity<ApiResponse<CancellationResponse>> cancel(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            HttpServletRequest request,
            Authentication authentication) {
        requireJson(contentType);
        UUID cancellationId = LocationRecommendationDtos.parseIdempotencyKey(idempotencyKey);
        var cancellationRequest = LocationRecommendationDtos.parseCancellationRequest(parseBody(request));
        return noStore(ApiResponse.of("location recommendation cancelled",
                service.cancel(users.resolve(authentication), cancellationId, cancellationRequest)));
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(response);
    }

    private void requireJson(String contentType) {
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType == null ? "" : contentType);
            if (!"application".equalsIgnoreCase(mediaType.getType())
                    || !"json".equalsIgnoreCase(mediaType.getSubtype())) {
                throw unsupportedMediaType();
            }
        } catch (InvalidMediaTypeException exception) {
            throw unsupportedMediaType();
        }
    }

    private JsonNode parseBody(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
                throw requestBodyTooLarge();
            }
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            if (body.length > MAX_REQUEST_BODY_BYTES) {
                throw requestBodyTooLarge();
            }
            return body.length == 0 ? null : mapper.readTree(body);
        } catch (IOException | JacksonException exception) {
            throw new LocationRecommendationException(400, "INVALID_REQUEST", "invalid request");
        }
    }

    private LocationRecommendationException requestBodyTooLarge() {
        return new LocationRecommendationException(413, "PAYLOAD_TOO_LARGE", "request body too large");
    }

    private LocationRecommendationException unsupportedMediaType() {
        return new LocationRecommendationException(415, "UNSUPPORTED_MEDIA_TYPE", "unsupported media type");
    }
}
