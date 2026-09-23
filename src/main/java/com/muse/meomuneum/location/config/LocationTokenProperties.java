package com.muse.meomuneum.location.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "location.token")
public record LocationTokenProperties(String secret) {
}
