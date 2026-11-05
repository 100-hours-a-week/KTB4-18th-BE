package com.muse.meomuneum.locationrecommendation.config;

import java.net.URI;
import java.time.Duration;

public record LocationRecommendationSettings(URI endpoint, Duration aiTimeout, Duration connectTimeout,
        Duration backendTimeout, boolean enabled) {
}
