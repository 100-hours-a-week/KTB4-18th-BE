package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.muse.meomuneum.musicrecord.config.MusicSearchStorageConfig;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class MusicSearchStorageServiceTest {
    @Test
    void succeedsOnThirdAttemptWithSeparateTransactionsAndStops() {
        var repository = mock(MusicRecordRepository.class);
        var manager = new TrackingTransactions();
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (calls.incrementAndGet() < 3) {
                throw new TransientDataAccessResourceException("temporary");
            }
            return null;
        }).when(repository).insertMusicIfAbsent(any());
        service(repository, manager, Duration.ZERO).store(List.of(item("1", "artist")));
        assertThat(calls).hasValue(3);
        assertThat(manager.events).containsExactly("begin", "rollback", "begin", "rollback", "begin", "commit");
        assertThat(manager.propagations).containsOnly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void exhaustsAfterFourAttemptsAndContinuesToNextTrack() {
        var repository = mock(MusicRecordRepository.class);
        MusicItem first = item("1", "artist");
        MusicItem second = item("2", "artist");
        doThrow(new TransientDataAccessResourceException("temporary")).when(repository).insertMusicIfAbsent(first);
        service(repository, new TrackingTransactions(), Duration.ZERO).store(List.of(first, second));
        verify(repository, times(4)).insertMusicIfAbsent(first);
        verify(repository).insertMusicIfAbsent(second);
    }

    @Test
    void permanentAndUnclassifiedFailuresHaveOneAttempt() {
        for (RuntimeException failure : List.of(new DataIntegrityViolationException("permanent"),
                new IllegalStateException("unclassified"), new CannotGetJdbcConnectionException("permanent"),
                new DataIntegrityViolationException("permanent", new SQLTransientConnectionException()))) {
            var repository = mock(MusicRecordRepository.class);
            doThrow(failure).when(repository).insertMusicIfAbsent(any());
            service(repository, new TrackingTransactions(), Duration.ZERO).store(List.of(item("1", "artist")));
            verify(repository).insertMusicIfAbsent(any());
        }
    }

    @Test
    void wrappedTransientConnectionFailureRetriesButPermanentCauseWins() {
        var repository = mock(MusicRecordRepository.class);
        doThrow(new CannotGetJdbcConnectionException("temporary", new SQLTransientConnectionException()))
                .doNothing().when(repository).insertMusicIfAbsent(any());
        service(repository, new TrackingTransactions(), Duration.ZERO).store(List.of(item("1", "artist")));
        verify(repository, times(2)).insertMusicIfAbsent(any());
        assertThat(MusicSearchStorageService.isRetryable(new TransientDataAccessResourceException("wrapper",
                new DataIntegrityViolationException("permanent")))).isFalse();
        var cycle = new IllegalStateException();
        var nested = new IllegalStateException(cycle);
        cycle.initCause(nested);
        assertThat(MusicSearchStorageService.isRetryable(cycle)).isFalse();
    }

    @Test
    void invalidTracksDoNotBlockUnicodeBoundaryOrNextValidTrack() {
        var repository = mock(MusicRecordRepository.class);
        MusicItem boundary = item("3", "🎵".repeat(1000));
        MusicItem last = item("4", "artist");
        service(repository, new TrackingTransactions(), Duration.ZERO).store(List.of(
                item("1", " "), item("2", "🎵".repeat(1001)),
                new MusicItem(null, "ITUNES", "9".repeat(65), "song", "artist", null, null, null, false),
                new MusicItem(null, "OTHER", "1", "song", "artist", null, null, null, false),
                new MusicItem(null, "ITUNES", "5", " ", "artist", null, null, null, false), boundary, last));
        verify(repository).insertMusicIfAbsent(boundary);
        verify(repository).insertMusicIfAbsent(last);
        verify(repository, times(2)).insertMusicIfAbsent(any());
    }

    @Test
    void fatalErrorPropagatesAndStopsSnapshot() {
        var repository = mock(MusicRecordRepository.class);
        doThrow(new AssertionError("fatal")).when(repository).insertMusicIfAbsent(any());
        assertThatThrownBy(() -> service(repository, new TrackingTransactions(), Duration.ZERO)
                .store(List.of(item("1", "artist"), item("2", "artist"))))
                .isInstanceOf(AssertionError.class);
        verify(repository).insertMusicIfAbsent(any());
    }

    @Test
    void productionBackoffIsOneTwoFourSecondsThenStopsAndRejectsInvalidConfiguration() {
        var config = new MusicSearchStorageConfig();
        var backoff = config.musicSearchStorageRetry(Duration.ofSeconds(1)).getRetryPolicy().getBackOff().start();
        assertThat(backoff.nextBackOff()).isEqualTo(1000);
        assertThat(backoff.nextBackOff()).isEqualTo(2000);
        assertThat(backoff.nextBackOff()).isEqualTo(4000);
        assertThat(backoff.nextBackOff()).isEqualTo(-1);
        assertThatThrownBy(() -> config.musicSearchStorageRetry(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.musicSearchStorageExecutor(0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.musicSearchStorageExecutor(1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.musicSearchStorageExecutor(1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveBackoffWaitsOutsideTransaction() {
        var repository = mock(MusicRecordRepository.class);
        var manager = new TrackingTransactions();
        var calls = new AtomicInteger();
        var instants = new ArrayList<Long>();
        doAnswer(invocation -> {
            instants.add(System.nanoTime());
            if (calls.incrementAndGet() == 1) {
                throw new TransientDataAccessResourceException("temporary");
            }
            return null;
        }).when(repository).insertMusicIfAbsent(any());
        var retry = retry(Duration.ofMillis(80));
        var observed = new AtomicBoolean();
        retry.setRetryListener(new RetryListener() {
            @Override
            public void onRetryableExecution(RetryPolicy policy, Retryable<?> operation, RetryState state) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (observed.compareAndSet(false, true)) {
                    assertThat(manager.events).containsExactly("begin", "rollback");
                }
            }
        });
        new MusicSearchStorageService(repository, retry, manager).store(List.of(item("1", "artist")));
        assertThat(observed).isTrue();
        assertThat(instants.get(1) - instants.getFirst()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(70));
        assertThat(manager.events).containsExactly("begin", "rollback", "begin", "commit");
    }

    @Test
    void interruptionDuringPositiveBackoffPreservesFlagAndStopsEntireSnapshot() throws Exception {
        var repository = mock(MusicRecordRepository.class);
        MusicItem first = item("1", "artist");
        doThrow(new TransientDataAccessResourceException("temporary")).when(repository).insertMusicIfAbsent(first);
        var retry = retry(Duration.ofSeconds(5));
        var failed = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var preserved = new AtomicBoolean();
        retry.setRetryListener(new RetryListener() {
            @Override
            public void onRetryableExecution(RetryPolicy policy, Retryable<?> operation, RetryState state) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                failed.countDown();
            }
            @Override
            public void onRetryPolicyInterruption(RetryPolicy policy, Retryable<?> operation, RetryException failure) {
                assertThat(failure.getCause()).isInstanceOf(TransientDataAccessResourceException.class);
                interrupted.set(true);
            }
        });
        var logger = (Logger) LoggerFactory.getLogger(MusicSearchStorageService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var worker = new Thread(() -> {
            try {
                new MusicSearchStorageService(repository, retry, new TrackingTransactions())
                        .store(List.of(first, item("2", "artist")));
                preserved.set(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        });
        try {
            worker.start();
            assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (worker.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(worker.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            worker.interrupt();
            worker.join(3000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(preserved).isTrue();
            assertThat(interrupted).isTrue();
            verify(repository).insertMusicIfAbsent(first);
            verify(repository, times(1)).insertMusicIfAbsent(any());
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("event=music_search_storage_interrupted"));
        } finally {
            worker.interrupt();
            worker.join(3000);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private MusicSearchStorageService service(MusicRecordRepository repository, TrackingTransactions manager,
            Duration delay) {
        return new MusicSearchStorageService(repository, retry(delay), manager);
    }

    private RetryTemplate retry(Duration delay) {
        return new RetryTemplate(RetryPolicy.builder().maxRetries(3).delay(delay).multiplier(2)
                .maxDelay(Duration.ofSeconds(10)).predicate(MusicSearchStorageService::isRetryable).build());
    }

    private MusicItem item(String id, String artist) {
        return new MusicItem(null, "ITUNES", id, "song", artist, null, null, null, false);
    }

    static class TrackingTransactions extends AbstractPlatformTransactionManager {
        final List<String> events = new ArrayList<>();
        final List<Integer> propagations = new ArrayList<>();
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
            propagations.add(definition.getPropagationBehavior());
        }
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }
}
