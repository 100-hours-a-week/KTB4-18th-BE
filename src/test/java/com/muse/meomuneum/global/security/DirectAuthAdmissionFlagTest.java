package com.muse.meomuneum.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DirectAuthAdmissionFlagTest {
    @Test
    void defaultsFalseAndBindsCanonicalProperty() {
        assertThat(new DirectAuthAdmissionFlag(new MockEnvironment()).enabled()).isFalse();
        assertThat(new DirectAuthAdmissionFlag(new MockEnvironment()
                .withProperty("auth.direct-v2.enabled", "true")).enabled()).isTrue();
    }

    @Test
    void aliasWinsAndMustBeBoolean() {
        var environment = new MockEnvironment().withProperty("auth.direct-v2.enabled", "true")
                .withProperty("AUTH_DIRECT_V2_ENABLED", "false");
        assertThat(new DirectAuthAdmissionFlag(environment).enabled()).isFalse();
        environment.setProperty("AUTH_DIRECT_V2_ENABLED", "true");
        assertThat(new DirectAuthAdmissionFlag(environment).enabled()).isTrue();
        environment.setProperty("AUTH_DIRECT_V2_ENABLED", "yes");
        assertThatThrownBy(() -> new DirectAuthAdmissionFlag(environment)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCanonicalValue() {
        var environment = new MockEnvironment().withProperty("auth.direct-v2.enabled", "yes");
        assertThatThrownBy(() -> new DirectAuthAdmissionFlag(environment)).isInstanceOf(IllegalArgumentException.class);
    }
}
