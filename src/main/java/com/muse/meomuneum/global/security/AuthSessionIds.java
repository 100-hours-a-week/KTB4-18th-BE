package com.muse.meomuneum.global.security;

import java.util.UUID;

/** Canonical session identifier contract shared by location issuance and chat validation. */
public final class AuthSessionIds {
    private AuthSessionIds() {
    }

    public static boolean isValid(String sid) {
        try {
            return sid != null && UUID.fromString(sid).toString().equals(sid);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
