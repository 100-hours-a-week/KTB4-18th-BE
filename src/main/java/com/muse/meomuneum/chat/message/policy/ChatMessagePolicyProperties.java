package com.muse.meomuneum.chat.message.policy;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatMessagePolicyProperties.Rules.class)
public class ChatMessagePolicyProperties {
    @ConfigurationProperties("chat.message-policy")
    public record Rules(Duration minimumInterval, boolean detectionEnabled) {
        public Rules {
            minimumInterval = minimumInterval == null ? Duration.ofSeconds(1) : minimumInterval;
            if (minimumInterval.isNegative() || minimumInterval.isZero()) {
                throw new IllegalArgumentException("chat minimum interval must be positive");
            }
        }
    }
}
