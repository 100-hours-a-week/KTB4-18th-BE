package com.muse.meomuneum.user.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;
import com.muse.meomuneum.user.settings.domain.MapVisibility;
import com.muse.meomuneum.user.settings.domain.UserSettings;
import com.muse.meomuneum.user.settings.repository.UserSettingsRepository;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSettingsRepository userSettingsRepository;

    @Test
    void createsMissingSettingsWithOneNativeUpsertBeforeReadingThem() {
        User user = mock(User.class);
        UserSettings settings = new UserSettings(1L, MapVisibility.PRIVATE, true,
                LocalDateTime.of(2026, 9, 26, 0, 0));
        UserSettingsService service = service();
        when(userRepository.findActiveByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(userSettingsRepository.findById(1L)).thenReturn(Optional.of(settings));

        assertThat(service.get(1L)).isSameAs(settings);

        InOrder inOrder = org.mockito.Mockito.inOrder(userRepository, userSettingsRepository);
        inOrder.verify(userRepository).findActiveByIdForUpdate(1L);
        inOrder.verify(userSettingsRepository).insertDefaultsIfAbsent(1L);
        inOrder.verify(userSettingsRepository).findById(1L);
    }

    @Test
    void updatesTimestampAfterNativeUpsertMakesTheSettingsRowAvailable() {
        User user = mock(User.class);
        UserSettings settings = mock(UserSettings.class);
        UserSettingsService service = service();
        when(userRepository.findActiveByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(userSettingsRepository.findById(1L)).thenReturn(Optional.of(settings));

        service.update(1L, MapVisibility.PUBLIC, false);

        verify(settings).update(MapVisibility.PUBLIC, false, LocalDateTime.of(2026, 9, 26, 0, 0));
    }

    private UserSettingsService service() {
        return new UserSettingsService(userSettingsRepository, userRepository,
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC));
    }
}
