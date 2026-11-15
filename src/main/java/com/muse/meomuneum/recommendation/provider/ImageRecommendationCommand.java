package com.muse.meomuneum.recommendation.provider;

import java.util.UUID;

public record ImageRecommendationCommand(UUID threadId, UUID requestId, byte[] image) {
}
