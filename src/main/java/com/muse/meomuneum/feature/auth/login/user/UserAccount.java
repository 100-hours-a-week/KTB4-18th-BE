package com.muse.meomuneum.feature.auth.login.user;

import java.time.LocalDateTime;

public record UserAccount(Long id, String email, String passwordHash, LocalDateTime deletedAt) {

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
