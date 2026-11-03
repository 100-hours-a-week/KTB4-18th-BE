package com.muse.meomuneum.locationrecommendation.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository;

@Component
public class LocationRecommendationCleanupJob {
    private final LocationRecommendationRepository repository;

    public LocationRecommendationCleanupJob(LocationRecommendationRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
    public void cleanup() {
        repository.cleanupExpired();
    }
}
