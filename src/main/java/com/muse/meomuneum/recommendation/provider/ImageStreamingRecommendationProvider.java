package com.muse.meomuneum.recommendation.provider;

import java.util.List;

import com.muse.meomuneum.recommendation.dto.TrackData;

public interface ImageStreamingRecommendationProvider {
    List<TrackData> recommend(ImageRecommendationCommand command, ImageRecommendationStreamListener listener);
}
