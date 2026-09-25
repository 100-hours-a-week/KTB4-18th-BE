package com.muse.meomuneum.musicrecord.resolver;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("musicRecordCurrentUserResolver")
public class CurrentUserResolver {
    public long resolve(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof Long userId)) throw new IllegalStateException("authenticated user is required");
        return userId;
    }
}
