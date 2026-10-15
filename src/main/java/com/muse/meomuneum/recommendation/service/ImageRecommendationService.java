package com.muse.meomuneum.recommendation.service;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.muse.meomuneum.recommendation.dto.request.ImageRecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationAcceptedResponse;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.media.RecommendationImageValidator;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

@Service
public class ImageRecommendationService {
    private final RecommendationRepository repository;
    private final RecommendationImageValidator validator;

    public ImageRecommendationService(RecommendationRepository repository, RecommendationImageValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public RecommendationAcceptedResponse accept(ImageRecommendationRequest request, MultipartFile image, Long userId) {
        if (userId == null) {
            throw new RecommendationException(401, "로그인이 필요합니다.");
        }

        String conversationKey;
        try {
            conversationKey = UUID.fromString(request.conversation_key()).toString();
        } catch (IllegalArgumentException exception) {
            throw new RecommendationException(400, "invalid image recommendation request");
        }

        if (image == null || image.isEmpty()) {
            throw new RecommendationException(400, "invalid image recommendation request");
        }
        if (image.getSize() > RecommendationImageValidator.MAX_BYTES) {
            throw new RecommendationException(413, "image too large");
        }

        byte[] imageBytes;
        try {
            imageBytes = image.getBytes();
        } catch (IOException exception) {
            throw new RecommendationException(400, "invalid image recommendation request");
        }
        try {
            validator.validate(imageBytes);
        } finally {
            Arrays.fill(imageBytes, (byte) 0);
        }

        var normalizedRequest = new ImageRecommendationRequest(conversationKey, request.input_type(),
                request.trigger_type());
        long recommendationId = repository.createImageSession(normalizedRequest, userId, Instant.now());
        return new RecommendationAcceptedResponse(recommendationId, "PROCESSING", conversationKey);
    }
}
