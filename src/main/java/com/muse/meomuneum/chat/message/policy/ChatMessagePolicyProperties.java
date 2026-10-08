package com.muse.meomuneum.chat.message.policy;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatMessagePolicyProperties.Rules.class)
public class ChatMessagePolicyProperties {
    @ConfigurationProperties("chat.message-policy")
    public record Rules(Duration minimumInterval, boolean detectionEnabled, boolean automaticBanEnabled,
            List<String> profanity, List<String> obscenity) {
        public Rules {
            profanity = profanity == null ? List.of("씨발", "개새끼") : List.copyOf(profanity);
            obscenity = obscenity == null ? List.of("자지 빨아", "보지 빨아") : List.copyOf(obscenity);
            if (profanity.stream().anyMatch(String::isBlank) || obscenity.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("chat detection terms must not be empty");
            }
            minimumInterval = minimumInterval == null ? Duration.ofSeconds(1) : minimumInterval;
            if (minimumInterval.isNegative() || minimumInterval.isZero()) {
                throw new IllegalArgumentException("chat minimum interval must be positive");
            }
        }
    }
}
