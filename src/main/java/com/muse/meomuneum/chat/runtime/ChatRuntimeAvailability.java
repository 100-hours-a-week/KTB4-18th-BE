package com.muse.meomuneum.chat.runtime;

import java.util.function.Predicate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.muse.meomuneum.chat.auth.ChatCoreSessionVerifier;
import com.muse.meomuneum.chat.message.relay.ChatMessageRelay;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;

/** Readiness observes existing guards; it never renews permits or reopens a failed Task. */
public final class ChatRuntimeAvailability implements HealthIndicator {
    private final Environment environment;
    private final ObjectProvider<ChatRuntimeLifecycle> lifecycles;
    private final ObjectProvider<ChatCoreSessionVerifier> verifiers;
    private final ObjectProvider<ChatParticipationService> participation;
    private final ObjectProvider<ChatRedisConnection> redis;
    private final ObjectProvider<ChatMessageRelay> relays;

    public ChatRuntimeAvailability(Environment environment, ObjectProvider<ChatRuntimeLifecycle> lifecycles,
            ObjectProvider<ChatCoreSessionVerifier> verifiers, ObjectProvider<ChatParticipationService> participation,
            ObjectProvider<ChatRedisConnection> redis, ObjectProvider<ChatMessageRelay> relays) {
        this.environment = environment;
        this.lifecycles = lifecycles;
        this.verifiers = verifiers;
        this.participation = participation;
        this.redis = redis;
        this.relays = relays;
    }

    public boolean accepting() {
        // Core availability is not a replacement for each user's bounded authorization permit.
        // In-flight requests retain their existing authentication and commit guards.
        try {
            return environment.getProperty("chat.runtime.enabled", Boolean.class, false)
                    && healthy(lifecycles, "chat.lifecycle.enabled", ChatRuntimeLifecycle::permitted)
                    && healthy(verifiers, "chat.core-auth.enabled", ignored -> true)
                    && healthy(participation, "chat.distributed-presence.enabled", ignored -> true)
                    && healthy(redis, "chat.redis.enabled", ignored -> true)
                    && healthy(relays, "chat.message-relay.enabled", ignored -> true);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public Health health() {
        try {
            boolean ready = accepting()
                    && healthy(verifiers, "chat.core-auth.enabled", ChatCoreSessionVerifier::isAvailable)
                    && healthy(participation, "chat.distributed-presence.enabled", ChatParticipationService::available)
                    && healthy(redis, "chat.redis.enabled",
                            connection -> connection.isConnected() && connection.areSubscriptionsReady())
                    && healthy(relays, "chat.message-relay.enabled", ChatMessageRelay::ready);
            return ready ? Health.up().build() : Health.down().build();
        } catch (RuntimeException unavailable) {
            // Health responses must not expose endpoints, SQL, credentials or dependency exceptions.
            return Health.down().build();
        }
    }

    private <T> boolean healthy(ObjectProvider<T> provider, String flag, Predicate<T> ready) {
        T dependency = provider.getIfAvailable();
        if (dependency != null) {
            return ready.test(dependency);
        }
        return !environment.acceptsProfiles(Profiles.of("prod"))
                && !environment.getProperty(flag, Boolean.class, false);
    }
}
