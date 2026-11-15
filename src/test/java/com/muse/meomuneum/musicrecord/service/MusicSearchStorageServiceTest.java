package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class MusicSearchStorageServiceTest {
    @Test
    void emptyOrNullSnapshotDoesNotAdmitWork() {
        var jobs = mock(MusicSearchIngestJobService.class);
        var service = new MusicSearchStorageService(jobs);
        service.store(null);
        service.store(List.of());
        verifyNoInteractions(jobs);
    }

    @Test
    void validatesEverySnapshotItemBeforeAdmittingAny() {
        var jobs = mock(MusicSearchIngestJobService.class);
        var service = new MusicSearchStorageService(jobs);
        MusicItem valid = item("1", "artist");
        MusicItem invalid = item("2", "🎵".repeat(1001));

        assertThatThrownBy(() -> service.store(List.of(valid, invalid)))
                .isInstanceOf(MusicMetadataException.class);

        verifyNoInteractions(jobs);
    }

    @Test
    void deduplicatesByProviderIdentityAndAdmitsWholeValidSnapshotSynchronously() {
        var jobs = mock(MusicSearchIngestJobService.class);
        var service = new MusicSearchStorageService(jobs);
        MusicItem first = item("1", "original");
        MusicItem duplicate = item("1", "replacement");
        MusicItem second = item("2", "artist");

        service.store(List.of(first, duplicate, second));

        verify(jobs).admit(List.of(first, second));
    }

    @Test
    void mapsAdmissionFailureToStableServiceUnavailableResponse() {
        var jobs = mock(MusicSearchIngestJobService.class);
        doThrow(new DataAccessResourceFailureException("private SQL detail")).when(jobs).admit(anyList());
        var service = new MusicSearchStorageService(jobs);

        assertThatThrownBy(() -> service.store(List.of(item("1", "artist"))))
                .isInstanceOfSatisfying(MusicRecordException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo("music_search_storage_unavailable");
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.publicMessage()).isEqualTo("music search storage unavailable");
                    assertThat(exception.publicMessage()).doesNotContain("private SQL detail");
                });
    }

    @Test
    void admissionFailureLogOmitsExceptionMessageAndProviderMetadata() {
        var jobs = mock(MusicSearchIngestJobService.class);
        doThrow(new DataAccessResourceFailureException("private SQL and password detail"))
                .when(jobs).admit(anyList());
        var logger = (Logger) LoggerFactory.getLogger(MusicSearchStorageService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var service = new MusicSearchStorageService(jobs);

        try {
            assertThatThrownBy(() -> service.store(List.of(new MusicItem(null, "ITUNES", "17",
                    "private-provider-title", "artist", null, null, null, false))))
                    .isInstanceOf(MusicRecordException.class);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("event=music_search_admission_failed",
                        "count=1", "exception=DataAccessResourceFailureException");
                assertThat(event.getFormattedMessage()).doesNotContain("private SQL and password detail",
                        "private-provider-title");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void validatesOneThousandCodePointsAndRejectsInvalidProviderIdentity() {
        MusicSearchStorageService.validate(item("1", "🎵".repeat(1000)));

        assertThatThrownBy(() -> MusicSearchStorageService.validate(
                new MusicItem(null, "OTHER", "2", "song", "artist", null, null, null, false)))
                .isInstanceOf(MusicMetadataException.class);
    }

    @Test
    void onlyClassifiedTransientFailuresAreRetried() {
        assertThat(MusicSearchStorageService.isRetryable(new DataAccessResourceFailureException("unavailable")))
                .isFalse();
        assertThat(MusicSearchStorageService.isRetryable(
                new org.springframework.dao.TransientDataAccessResourceException("temporary"))).isTrue();
        assertThat(MusicSearchStorageService.isRetryable(
                new DataAccessResourceFailureException("connection dropped", new SQLException("lost", "08S01"))))
                .isTrue();
        assertThat(MusicSearchStorageService.isRetryable(
                new DataAccessResourceFailureException("authentication failed",
                        new SQLException("denied", "28000"))))
                .isFalse();
        assertThat(MusicSearchStorageService.isRetryable(new DataIntegrityViolationException("invalid"))).isFalse();
    }

    private MusicItem item(String id, String artist) {
        return new MusicItem(null, "ITUNES", id, "song", artist, null, null, null, false);
    }
}
