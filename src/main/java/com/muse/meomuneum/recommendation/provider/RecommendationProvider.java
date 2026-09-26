package com.muse.meomuneum.recommendation.provider;

import java.util.List;

import com.muse.meomuneum.recommendation.dto.TrackData;

public interface RecommendationProvider {
    List<TrackData> recommend(RecommendationCommand command);

    default String providerName() {
        return getClass().getSimpleName();
    }
}
