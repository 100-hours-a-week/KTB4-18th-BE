package com.muse.meomuneum.recommendation.provider;

import java.util.List;

import com.muse.meomuneum.recommendation.dto.TrackData;

public interface RecommendationStreamListener {
    void onText(String delta);

    void onTracks(List<TrackData> tracks);
}
