package com.muse.meomuneum.recommendation.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.dto.TrackData;

class LocalMockImageRecommendationProviderTests {
    private static final ImageRecommendationCommand COMMAND = new ImageRecommendationCommand(
            java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
            java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"), new byte[]{1, 2, 3});

    @Test
    void returnsStableTracksAfterTheUsableVerdictAndText() {
        var firstEvents = new ArrayList<String>();
        var secondEvents = new ArrayList<String>();
        var provider = new LocalMockImageRecommendationProvider("usable");

        List<TrackData> first = provider.recommend(COMMAND, listener(firstEvents));
        List<TrackData> second = provider.recommend(COMMAND, listener(secondEvents));

        assertThat(first).containsExactlyElementsOf(second).hasSize(3);
        assertThat(first).extracting(TrackData::provider).containsOnly("LOCAL_MOCK");
        assertThat(first).extracting(TrackData::externalId).doesNotHaveDuplicates();
        assertThat(firstEvents).containsExactly("verdict:true", "text", "tracks:3");
        assertThat(secondEvents).isEqualTo(firstEvents);
    }

    @Test
    void emitsRejectedVerdictAndNoTracksForTheReshootFlow() {
        var events = new ArrayList<String>();
        var provider = new LocalMockImageRecommendationProvider("rejected");

        List<TrackData> tracks = provider.recommend(COMMAND, listener(events));

        assertThat(tracks).isEmpty();
        assertThat(events).containsExactly("verdict:false", "rejected:scene_unreadable", "tracks:0");
    }

    @Test
    void rejectsUnknownMockOutcome() {
        assertThatThrownBy(() -> new LocalMockImageRecommendationProvider("other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recommendation.image.mock.outcome must be 'usable' or 'rejected'");
    }

    @Test
    void rejectsUnknownImageProviderSelection() {
        assertThatThrownBy(() -> ImageRecommendationProviderSelectionValidator.validate("typo", "usable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recommendation.image.provider must be 'ai' or 'mock'");
    }

    @Test
    void rejectsWhitespacePaddedImageProviderSelection() {
        assertThatThrownBy(() -> ImageRecommendationProviderSelectionValidator.validate(" mock ", "usable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recommendation.image.provider must be 'ai' or 'mock'");
    }

    @Test
    void rejectsUnknownOutcomeWhenMockProviderIsSelected() {
        assertThatThrownBy(() -> ImageRecommendationProviderSelectionValidator.validate("mock", "other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recommendation.image.mock.outcome must be 'usable' or 'rejected'");
    }

    @Test
    void rejectsWhitespacePaddedMockOutcome() {
        assertThatThrownBy(() -> new LocalMockImageRecommendationProvider(" usable "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recommendation.image.mock.outcome must be 'usable' or 'rejected'");
    }

    private ImageRecommendationStreamListener listener(List<String> events) {
        return new ImageRecommendationStreamListener() {
            @Override
            public void onVerdict(boolean usable) {
                events.add("verdict:" + usable);
            }

            @Override
            public void onRejected(String reason) {
                events.add("rejected:" + reason);
            }

            @Override
            public void onText(String delta) {
                events.add("text");
            }

            @Override
            public void onTracks(List<TrackData> tracks) {
                events.add("tracks:" + tracks.size());
            }
        };
    }
}
