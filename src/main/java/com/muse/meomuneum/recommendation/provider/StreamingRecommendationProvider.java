package com.muse.meomuneum.recommendation.provider;

import java.util.List;

import com.muse.meomuneum.recommendation.dto.TrackData;

public interface StreamingRecommendationProvider extends RecommendationProvider {
    List<TrackData> recommend(RecommendationCommand command, RecommendationStreamListener listener);

    @Override
    default List<TrackData> recommend(RecommendationCommand command) {
        return recommend(command, new RecommendationStreamListener() {
            @Override
            public void onText(String delta) {
                // 동기 호출과 기존 제공자 테스트는 스트림 소비자가 없어도 동작합니다.
            }

            @Override
            public void onTracks(List<TrackData> tracks) {
                // 동기 호출과 기존 제공자 테스트는 스트림 소비자가 없어도 동작합니다.
            }
        });
    }
}
