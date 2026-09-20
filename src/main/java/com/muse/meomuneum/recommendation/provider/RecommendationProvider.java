package com.muse.meomuneum.recommendation.provider;

import com.muse.meomuneum.recommendation.dto.TrackData;

import java.util.List;

public interface RecommendationProvider {
    List<TrackData> recommend(List<String> prompts);
}
