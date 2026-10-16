package com.muse.meomuneum.recommendation.provider;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.dto.TrackData;

@Component
@ConditionalOnProperty(prefix = "recommendation.image", name = "provider", havingValue = "mock")
public class LocalMockImageRecommendationProvider implements ImageStreamingRecommendationProvider {
    private static final String REJECTION_REASON = "scene_unreadable";
    private static final List<TrackData> TRACKS = List.of(
            new TrackData("LOCAL_MOCK", "local-image-001", "Evening Window", "Local Sample", null, null,
                    null, "A calm, unhurried mood for the end of the day."),
            new TrackData("LOCAL_MOCK", "local-image-002", "Soft Light", "Local Sample", null, null, null,
                    "A gentle instrumental for a quiet walk."),
            new TrackData("LOCAL_MOCK", "local-image-003", "After the Rain", "Local Sample", null, null, null,
                    "A warm track for a reflective moment."));

    private final MockOutcome outcome;

    public LocalMockImageRecommendationProvider(
            @Value("${recommendation.image.mock.outcome:usable}") String outcome) {
        this.outcome = MockOutcome.parse(outcome);
    }

    @Override
    public List<TrackData> recommend(ImageRecommendationCommand command, ImageRecommendationStreamListener listener) {
        if (outcome == MockOutcome.REJECTED) {
            listener.onVerdict(false);
            listener.onRejected(REJECTION_REASON);
            listener.onTracks(List.of());
            return List.of();
        }

        listener.onVerdict(true);
        listener.onText("차분한 분위기에 어울리는 곡을 골랐어요.");
        listener.onTracks(TRACKS);
        return TRACKS;
    }

    private enum MockOutcome {
        USABLE, REJECTED;

        private static MockOutcome parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "recommendation.image.mock.outcome must be 'usable' or 'rejected'");
            }
        }
    }
}
