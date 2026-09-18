package com.muse.meomuneum.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenResponse(@JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn) {
}
