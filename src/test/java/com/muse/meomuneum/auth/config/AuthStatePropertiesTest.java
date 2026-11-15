package com.muse.meomuneum.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthStatePropertiesTest {

    @Test
    void calculatesMajorityForHazelcastSplitBrainProtection() {
        assertThat(properties(3).minimumHazelcastMembers()).isEqualTo(2);
        assertThat(properties(2).minimumHazelcastMembers()).isEqualTo(2);
        assertThat(properties(1).minimumHazelcastMembers()).isEqualTo(1);
    }

    @Test
    void defaultsLocalBcryptConcurrencyWhenUnsetOrInvalid() {
        var properties = new AuthStateProperties("01234567890123456789012345678901", "", 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0);

        assertThat(properties.sessionIdleSeconds()).isEqualTo(1_209_600);
        assertThat(properties.sessionAbsoluteSeconds()).isEqualTo(7_776_000);
        assertThat(properties.localBcryptConcurrency()).isEqualTo(150);
    }

    private AuthStateProperties properties(int expectedMembers) {
        return new AuthStateProperties("01234567890123456789012345678901", "", 1_209_600, 7_776_000,
                10, 64, 150, 86_400, expectedMembers, 100_000, 300, 45);
    }
}
