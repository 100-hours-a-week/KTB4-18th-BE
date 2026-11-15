package com.muse.meomuneum.global.security;

import com.muse.meomuneum.user.domain.User;

/** User existence check only; session revocation validation belongs to the Core integration. */
@FunctionalInterface
public interface ActiveUserLookup {
    User findActiveUser(Long userId);
}
