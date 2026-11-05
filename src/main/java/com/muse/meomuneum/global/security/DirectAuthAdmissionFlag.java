package com.muse.meomuneum.global.security;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

public class DirectAuthAdmissionFlag {
    private final boolean enabled;

    public DirectAuthAdmissionFlag(Environment environment) {
        String value = environment.getProperty("AUTH_DIRECT_V2_ENABLED");
        if (value == null) {
            value = Binder.get(environment).bind("auth.direct-v2.enabled", String.class).orElse("false");
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("auth.direct-v2.enabled must be true or false");
        }
        enabled = Boolean.parseBoolean(value);
    }

    public boolean enabled() {
        return enabled;
    }
}
