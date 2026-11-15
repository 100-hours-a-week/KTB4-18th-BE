package com.muse.meomuneum.auth.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeviceSession(String sid, boolean current,
        @JsonProperty("device_label") String deviceLabel,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("last_refreshed_at") Instant lastRefreshedAt) {
}
