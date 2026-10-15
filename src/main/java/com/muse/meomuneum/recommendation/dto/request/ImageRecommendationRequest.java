package com.muse.meomuneum.recommendation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ImageRecommendationRequest(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String conversation_key,
        @NotBlank @Pattern(regexp = "IMAGE") String input_type,
        @NotBlank @Pattern(regexp = "CHATBOT") String trigger_type) {
}
