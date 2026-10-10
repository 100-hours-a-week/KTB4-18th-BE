package com.muse.meomuneum.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.muse.meomuneum.user.account.exception.UserAccountException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

class ProfileImageServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final ProfileImageStorage storage = mock(ProfileImageStorage.class);
    private final User user = mock(User.class);
    private final MockMultipartFile image = new MockMultipartFile("image", new byte[]{1});
    private final ProfileImageService service = new ProfileImageService(repository, storage);

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        when(repository.findActiveByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(user.getProfileImageUrl()).thenReturn("previous");
        when(storage.store(1L, image)).thenReturn("replacement");
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void locksUserPreservesProfileAndDefersOldDeletionUntilCommit() {
        assertThat(service.upload(1L, image)).isEqualTo("replacement");
        verify(repository).findActiveByIdForUpdate(1L);
        verify(user).updateProfile(isNull(), isNull(), eq(false), isNull(), eq(false), eq("replacement"), any());
        verify(repository).flush();
        verify(storage, never()).delete(any(), any());
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).delete(1L, "previous");
        verify(storage, never()).delete(1L, "replacement");
    }

    @Test
    void flushFailureRegistersRollbackCleanupBeforeFailureAndKeepsPreviousFile() {
        doThrow(new IllegalStateException("flush failed")).when(repository).flush();
        assertThatThrownBy(() -> service.upload(1L, image)).isInstanceOf(IllegalStateException.class);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).delete(1L, "replacement");
        verify(storage, never()).delete(1L, "previous");
    }

    @Test
    void cleanupFailureDoesNotUndoCommittedReplacement() {
        doThrow(new IllegalStateException("cleanup failed")).when(storage).delete(1L, "previous");
        service.upload(1L, image);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage, never()).delete(1L, "replacement");
    }

    @Test
    void validationFailureDoesNotMutatePreviousProfile() {
        doThrow(new IllegalArgumentException("invalid image")).when(storage).store(1L, image);
        assertThatThrownBy(() -> service.upload(1L, image)).isInstanceOf(IllegalArgumentException.class);
        verify(user, never()).updateProfile(any(), any(), anyBoolean(), any(), anyBoolean(), any(), any());
        verify(repository, never()).flush();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void servesOnlyCurrentUrlWithinAuthenticatedUserNamespace() {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(user));
        String url = ProfileImageStorage.URL_PREFIX + "own.png";
        when(user.getProfileImageUrl()).thenReturn(url);
        when(storage.read(1L, url)).thenReturn(new byte[]{1, 2});
        assertThat(service.read(1L, "own")).containsExactly(1, 2);
        assertThatThrownBy(() -> service.read(1L, "stale")).isInstanceOf(UserAccountException.class);
        verify(storage, never()).read(1L, ProfileImageStorage.URL_PREFIX + "stale.png");
    }

    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(status));
    }
}
