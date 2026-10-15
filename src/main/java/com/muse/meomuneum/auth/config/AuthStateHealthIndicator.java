package com.muse.meomuneum.auth.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.hazelcast.core.HazelcastInstance;

@Component("authState")
public class AuthStateHealthIndicator implements HealthIndicator {

    private final ObjectProvider<HazelcastInstance> hazelcastProvider;
    private final AuthStateProperties properties;

    public AuthStateHealthIndicator(ObjectProvider<HazelcastInstance> hazelcastProvider,
            AuthStateProperties properties) {
        this.hazelcastProvider = hazelcastProvider;
        this.properties = properties;
    }

    @Override
    public Health health() {
        HazelcastInstance client = hazelcastProvider.getIfAvailable();
        if (client == null || !client.getLifecycleService().isRunning()) {
            return Health.down().withDetail("state", "unavailable").build();
        }
        try {
            int members = client.getCluster().getMembers().size();
            return members < properties.minimumHazelcastMembers()
                    || members > properties.expectedHazelcastMembers()
                            ? Health.down().withDetail("state", "unavailable").build()
                            : Health.up().withDetail("state", "connected").withDetail("members", members)
                                    .withDetail("minimumMembers", properties.minimumHazelcastMembers()).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("state", "unavailable").build();
        }
    }
}
