package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.security.DirectAuthRestoreSafety;

class DirectAuthRestoreSafetyProfileTest {
    @Test
    void localSafetyIsAvailableLocallyAndRejectedWithActiveOrDefaultProdProfile() {
        var environment = new MockEnvironment().withProperty("auth.direct-v2.safety-mode", "LOCAL")
                .withProperty("auth.direct-v2.backend-instances", "1");
        var safety = new DirectAuthRestoreSafety(environment);
        for (String[] profiles : new String[][]{{}, {"local"}, {"dev"}, {"local", "test"}}) {
            environment.setActiveProfiles(profiles);
            assertThatCode(() -> safety.require(1, "LOCAL_READY", true)).doesNotThrowAnyException();
        }
        for (String[] profiles : new String[][]{{"prod"}, {"local", "prod"}, {"prod", "test"}}) {
            environment.setActiveProfiles(profiles);
            assertThatThrownBy(() -> safety.require(1, "LOCAL_READY", true))
                    .isInstanceOf(DirectAuthFailure.class).hasMessage("AUTH_STATE_UNAVAILABLE");
        }
        environment.setActiveProfiles();
        environment.setDefaultProfiles("prod");
        assertThatThrownBy(() -> safety.require(1, "LOCAL_READY", true)).isInstanceOf(DirectAuthFailure.class);
    }

    @Test
    void prodProductionSafetyRequiresCurrentExternalEpochAndReadyDatabase() {
        var environment = new MockEnvironment().withProperty("auth.direct-v2.safety-mode", "PRODUCTION")
                .withProperty("auth.direct-v2.backend-instances", "1")
                .withProperty("auth.direct-v2.external-restore-epoch", "2");
        environment.setActiveProfiles("prod");
        var safety = new DirectAuthRestoreSafety(environment);
        assertThatCode(() -> safety.require(2, "PRODUCTION_READY", true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> safety.require(1, "PRODUCTION_READY", true)).isInstanceOf(DirectAuthFailure.class);
        assertThatThrownBy(() -> safety.require(2, "LOCAL_READY", true)).isInstanceOf(DirectAuthFailure.class);
        assertThatThrownBy(() -> safety.require(2, "PRODUCTION_READY", false)).isInstanceOf(DirectAuthFailure.class);
        environment.withProperty("auth.direct-v2.external-restore-epoch", "02");
        assertThatThrownBy(() -> safety.require(2, "PRODUCTION_READY", true)).isInstanceOf(DirectAuthFailure.class);
    }
}
