package com.muse.meomuneum.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RecommendationImageEventStreamTests {
    @Test
    void retainsOrderedImageEventsForSameIdReplay() {
        RecommendationEventStream stream = new RecommendationEventStream();
        stream.registerImage(17L);
        stream.verdict(17L, true);
        stream.text(17L, "hello ");
        stream.text(17L, "world");
        stream.tracks(17L, Map.of("tracks", java.util.List.of()));
        stream.done(17L, Map.of("recommendation_id", 17L));

        assertThat(stream.imageJournalEventNames(17L)).containsExactly("verdict", "text", "tracks", "done");
    }

    @Test
    void streamLimitPreservesEarlierEventsForOrchestratorError() {
        RecommendationEventStream stream = new RecommendationEventStream();
        stream.registerImage(18L);
        assertThatThrownBy(() -> stream.text(18L, "x".repeat(65 * 1024)))
                .isInstanceOf(IllegalStateException.class);
        stream.verdict(18L, true);
        stream.error(18L, "safe failure");
        assertThat(stream.imageJournalEventNames(18L)).containsExactly("verdict", "error");
    }
}
