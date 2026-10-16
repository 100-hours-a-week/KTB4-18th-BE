package com.muse.meomuneum.recommendation.provider;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ImageRecommendationProviderSelectionValidator {
    private static final Set<String> PROVIDERS = Set.of("ai", "mock");
    private static final Set<String> MOCK_OUTCOMES = Set.of("usable", "rejected");

    public ImageRecommendationProviderSelectionValidator(
            @Value("${recommendation.image.provider:ai}") String provider,
            @Value("${recommendation.image.mock.outcome:usable}") String mockOutcome) {
        validate(provider, mockOutcome);
    }

    static void validate(String provider, String mockOutcome) {
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalStateException("recommendation.image.provider must be 'ai' or 'mock'");
        }
        if ("mock".equals(provider) && !MOCK_OUTCOMES.contains(mockOutcome)) {
            throw new IllegalStateException("recommendation.image.mock.outcome must be 'usable' or 'rejected'");
        }
    }
}
