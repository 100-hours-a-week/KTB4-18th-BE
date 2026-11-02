package com.muse.meomuneum.auth.directv2.security;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;

@Component
public class DirectAuthRestoreSafety {
    private final Environment environment;

    public DirectAuthRestoreSafety(Environment environment) {
        this.environment = environment;
    }

    public void require(long generation, String readiness, boolean verified) {
        String mode = environment.getProperty("auth.direct-v2.safety-mode", "OFF");
        if (generation <= 0 || !verified) {
            throw DirectAuthFailure.unavailable();
        }
        String instances = environment.getProperty("auth.direct-v2.backend-instances", "");
        if ("LOCAL".equals(mode) && "LOCAL_READY".equals(readiness) && "1".equals(instances)
                && !environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        String epoch = environment.getProperty("auth.direct-v2.external-restore-epoch", "");
        if (!"PRODUCTION".equals(mode) || !"PRODUCTION_READY".equals(readiness) || !"1".equals(instances)
                || !epoch.matches("[1-9][0-9]{0,18}")) {
            throw DirectAuthFailure.unavailable();
        }
        try {
            if (Long.parseLong(epoch) != generation) {
                throw DirectAuthFailure.unavailable();
            }
        } catch (NumberFormatException invalid) {
            throw DirectAuthFailure.unavailable();
        }
    }
}
