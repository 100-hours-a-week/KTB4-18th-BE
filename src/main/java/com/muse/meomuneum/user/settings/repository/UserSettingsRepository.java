package com.muse.meomuneum.user.settings.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.muse.meomuneum.user.settings.domain.UserSettings;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO user_settings (user_id) VALUES (:userId)
            ON DUPLICATE KEY UPDATE user_id = VALUES(user_id)
            """, nativeQuery = true)
    void insertDefaultsIfAbsent(@Param("userId") Long userId);
}
