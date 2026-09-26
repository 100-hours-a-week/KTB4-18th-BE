package com.muse.meomuneum.user.settings.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.user.repository.UserRepository;
import com.muse.meomuneum.user.settings.domain.MapVisibility;
import com.muse.meomuneum.user.settings.domain.UserSettings;
import com.muse.meomuneum.user.settings.repository.UserSettingsRepository;

@Service
public class UserSettingsService {

    private final UserSettingsRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public UserSettingsService(UserSettingsRepository repository, UserRepository userRepository) {
        this(repository, userRepository, Clock.systemUTC());
    }

    UserSettingsService(UserSettingsRepository repository, UserRepository userRepository, Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public UserSettings get(Long userId) {
        userRepository.findActiveByIdForUpdate(userId).orElseThrow(IllegalStateException::new);
        repository.insertDefaultsIfAbsent(userId);
        return repository.findById(userId).orElseThrow(IllegalStateException::new);
    }

    @Transactional
    public UserSettings update(Long userId, MapVisibility mapVisibility, Boolean isUnrecordedDotRecommendationEnabled) {
        UserSettings settings = get(userId);
        settings.update(mapVisibility, isUnrecordedDotRecommendationEnabled, now());
        return settings;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
