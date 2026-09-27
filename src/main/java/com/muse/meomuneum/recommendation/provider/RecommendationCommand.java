package com.muse.meomuneum.recommendation.provider;

import java.util.UUID;

public record RecommendationCommand(UUID threadId, UUID requestId, String message) {
}
