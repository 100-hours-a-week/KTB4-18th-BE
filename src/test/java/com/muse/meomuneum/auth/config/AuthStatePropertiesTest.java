package com.muse.meomuneum.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthStatePropertiesTest {

    @Test
    void calculatesMajorityForHazelcastSplitBrainProtection() {
        var threeMembers = properties(3);
        var twoMembers = properties(2);
        var oneMember = properties(1);

        assertThat(threeMembers.minimumHazelcastMembers()).isEqualTo(2);
        assertThat(twoMembers.minimumHazelcastMembers()).isEqualTo(2);
        assertThat(oneMember.minimumHazelcastMembers()).isEqualTo(1);
    }

    @Test
    void defaultsLocalBcryptConcurrencyToOneHundredFiftyWhenUnsetOrInvalid() {
        var defaults = properties(1, 0);

        assertThat(defaults.localBcryptConcurrency()).isEqualTo(150);
    }

    @Test
    void derivesTheExpectedActiveTicketAndOperationRecordCapacities() {
        var derived = new AuthStateProperties("01234567890123456789012345678901", "", "MEO_QUEUE_V2",
                1, 1, 900, 60, 900, 2_000, 10, 64, 150, 20, 120, 1_800, 5, 2_000, 0,
                86_400, 3, 100_000, 300, 45);
        var configured = new AuthStateProperties("01234567890123456789012345678901", "", "MEO_QUEUE_V2",
                1, 1, 900, 60, 900, 2_000, 10, 64, 150, 20, 120, 1_800, 5, 2_000, 4_000,
                86_400, 3, 100_000, 300, 45);

        assertThat(derived.queueCapacity() + derived.localBcryptConcurrency()).isEqualTo(2_150);
        assertThat(derived.operationRecordCapacity()).isEqualTo(3_174);
        assertThat(configured.operationRecordCapacity()).isEqualTo(4_000);
        assertThat(derived.maxActiveTickets()).isEqualTo(150);
    }

    @Test
    void activeTicketLimitIsIndependentFromQueueAndBcryptCapacity() {
        var configured = new AuthStateProperties("01234567890123456789012345678901", "", "MEO_QUEUE_V2",
                1, 1, 900, 60, 900, 2_000, 10, 64, 150, 20, 120, 1_800, 5, 2_000, 4_000,
                86_400, 1, 100_000, 300, 45, 150);

        assertThat(configured.maxActiveTickets()).isEqualTo(150);
        assertThat(configured.queueCapacity()).isEqualTo(2_000);
        assertThat(configured.localBcryptConcurrency()).isEqualTo(150);
    }

    private AuthStateProperties properties(int expectedMembers) {
        return properties(expectedMembers, 1);
    }

    private AuthStateProperties properties(int expectedMembers, int localBcryptConcurrency) {
        return new AuthStateProperties("01234567890123456789012345678901", "", "MEO_QUEUE_V2", 1, 1,
                1, 1, 1, 1, 1, 1, localBcryptConcurrency, 2, 2, 1, 1, 1, 1, 1,
                expectedMembers, 1, 1, 1);
    }
}
