package com.muse.meomuneum.feature.auth.login.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "auth.token")
public record AuthTokenProperties(
        @NotBlank String secret,
        @Min(1) long accessExpirationSeconds,
        @Min(1) long refreshExpirationSeconds) {
}
