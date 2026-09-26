package com.muse.meomuneum.user.settings.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "map_visibility", nullable = false)
    private MapVisibility mapVisibility;

    @Column(name = "is_unrecorded_dot_recommendation_enabled", nullable = false)
    private boolean isUnrecordedDotRecommendationEnabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserSettings() {
    }

    public UserSettings(Long userId, MapVisibility mapVisibility, boolean isUnrecordedDotRecommendationEnabled,
            LocalDateTime updatedAt) {
        this.userId = userId;
        this.mapVisibility = mapVisibility;
        this.isUnrecordedDotRecommendationEnabled = isUnrecordedDotRecommendationEnabled;
        this.updatedAt = updatedAt;
    }

    public void update(MapVisibility mapVisibility, Boolean isUnrecordedDotRecommendationEnabled,
            LocalDateTime updatedAt) {
        if (mapVisibility != null) {
            this.mapVisibility = mapVisibility;
        }
        if (isUnrecordedDotRecommendationEnabled != null) {
            this.isUnrecordedDotRecommendationEnabled = isUnrecordedDotRecommendationEnabled;
        }
        this.updatedAt = updatedAt;
    }

    public MapVisibility getMapVisibility() {
        return mapVisibility;
    }

    public boolean isUnrecordedDotRecommendationEnabled() {
        return isUnrecordedDotRecommendationEnabled;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
