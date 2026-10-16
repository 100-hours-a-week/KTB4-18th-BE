package com.muse.meomuneum.auth.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class HazelcastAuthStateConfigurationTest {

    @Test
    void refusesDeploymentConfiguredForMultipleBackendReplicas() {
        HazelcastAuthStateConfiguration configuration = new HazelcastAuthStateConfiguration();

        assertThatThrownBy(() -> configuration.authHazelcastClient(
                "meomuneum-auth", "localhost:5701", Duration.ofSeconds(1), "2", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_BACKEND_REPLICA_COUNT must be explicitly set to 1");
    }
}
