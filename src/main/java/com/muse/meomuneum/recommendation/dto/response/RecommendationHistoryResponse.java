package com.muse.meomuneum.recommendation.dto.response;

import java.util.List;

public record RecommendationHistoryResponse(List<Group> groups, String next_cursor, boolean has_next) {
    public record Group(String date, List<Recommendation> recommendations) {
    }

    public record Recommendation(long recommendation_id, String status, List<Item> items) {
    }

    public record Item(int rank_no, long music_id, String title, String artist_name) {
    }
}
