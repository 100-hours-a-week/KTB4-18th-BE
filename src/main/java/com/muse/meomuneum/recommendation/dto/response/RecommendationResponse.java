package com.muse.meomuneum.recommendation.dto.response;

import java.time.Instant;
import java.util.List;

public record RecommendationResponse(
        long recommendation_id, String status, String conversation_key,
        List<Item> items, Instant completed_at
) {
    public record Item(int rank_no, Music music) {}
    public record Music(long music_id, String title, String artist_name,
                        String album_cover_url, String preview_url) {}
}
