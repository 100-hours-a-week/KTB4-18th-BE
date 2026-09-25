package com.muse.meomuneum.location.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "location.reverse-geocoding")
public record ReverseGeocodingProperties(String baseUrl, String restApiKey, Duration connectTimeout,
        Duration readTimeout) {
}
