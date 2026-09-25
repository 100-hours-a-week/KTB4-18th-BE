package com.muse.meomuneum.recommendation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 음성 전사 결과도 텍스트와 동일한 추천 경로를 사용합니다.
public record RecommendationRequest(@NotBlank @Pattern(regexp = "TEXT|VOICE") String input_type,
        @NotBlank @Pattern(regexp = "CHATBOT") String trigger_type,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String conversation_key,
        @NotBlank(message = "추천받고 싶은 상황을 입력해 주세요.") @Size(max = 1000, message = "입력은 1000자 이내로 작성해 주세요.") String prompt) {
}
