package com.muse.meomuneum.chat.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.runtime.ChatRuntimeLifecycle;
import com.muse.meomuneum.chat.runtime.ChatRuntimeMetrics;
import com.muse.meomuneum.chat.view.ChatViewStorageService;
import com.muse.meomuneum.location.security.LocationResolutionClaims;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ChatParticipationDiagnosticsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ChatParticipationDiagnostics.class);
    private final Logger runtimeLogger = (Logger) LoggerFactory.getLogger(ChatRuntimeLifecycle.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
        runtimeLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        runtimeLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void retainsSqlMetadataAndApplicationSiteWithoutMessagesSqlOrThrowableProxy() {
        var sql = new SQLException("SELECT secret-canary FROM users WHERE token='bearer-canary'", "40001", 1213);
        sql.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "com.muse.meomuneum.chat.participation.ChatParticipationService", "project", "ignored.java", 42)});
        var failure = new DataAccessResourceFailureException("password-canary", sql);
        var diagnostics = new ChatParticipationDiagnostics("test", "synthetic-task");
        diagnostics.closed("redis_projection", failure, System.nanoTime());

        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getFormattedMessage()).contains("event=chat_participation_first_closed",
                "stage=redis_projection", "sql_state=40001", "sql_code=1213",
                "java.sql.SQLException", "DataAccessResourceFailureException",
                "ChatParticipationService.project:42").doesNotContain("secret-canary", "bearer-canary",
                        "password-canary", "SELECT", "ignored.java");
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage()).containsPattern("elapsed_ms=[0-9]+");
    }

    @Test
    void rejectsUntrustedStageSqlStateAndInjectedStackSymbols() {
        var sql = new SQLException("message-canary", "bad-state\nstate-canary", 123);
        sql.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.muse.bad\nframe-canary",
                "bad\nmethod-canary", "filename-canary", 1)});
        new ChatParticipationDiagnostics("test", "synthetic-task")
                .closed("bad\nstage-canary", sql, System.nanoTime());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("stage=unknown", "sql_state=none")
                .doesNotContain("canary", "\n", "\r");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void recordsOnlyOneCloseAcrossConcurrentCallers() throws Exception {
        var diagnostics = new ChatParticipationDiagnostics("test", "synthetic-task");
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 64; i++) {
                futures.add(workers.submit(() -> diagnostics.closed("concurrent", null, System.nanoTime())));
            }
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(appender.list).hasSize(1);
    }

    @Test
    void transactionEvidenceAndFirstCloseHaveIndependentBudgetsAndBoundedCyclicCauses() {
        var one = new RuntimeException("one-canary");
        var two = new RuntimeException("two-canary", one);
        one.initCause(two);
        var diagnostics = new ChatParticipationDiagnostics("test", "synthetic-task");
        diagnostics.transactionFailed("first_transaction", one, System.nanoTime());
        diagnostics.transactionFailed("later_transaction", two, System.nanoTime());
        diagnostics.closed("first_close", null, System.nanoTime());
        diagnostics.closed("later_close", two, System.nanoTime());

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("event=chat_participation_first_transaction_failure", "stage=first_transaction")
                .doesNotContain("canary", "later_transaction");
        assertThat(appender.list.getLast().getFormattedMessage()).contains("stage=first_close")
                .doesNotContain("later_close");
    }

    @Test
    void loggingDoesNotSuppressExistingRepeatedSocketShutdown() {
        var sockets = mock(ChatSocketSessions.class);
        var service = service(mock(PlatformTransactionManager.class), sockets);
        service.failClosed("first_guard", new RuntimeException("token-canary"), System.nanoTime());
        service.failClosed();

        assertThat(service.isFailed()).isTrue();
        verify(sockets, times(2)).closeAll(1013, "CHAT_SERVICE_UNAVAILABLE");
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("stage=first_guard")
                .doesNotContain("token-canary");
    }

    @Test
    void expiredRuntimePermitIsObservedBeforeHeartbeatLatchesTheServiceClosed() {
        var sockets = mock(ChatSocketSessions.class);
        var service = service(mock(PlatformTransactionManager.class), sockets);
        ReflectionTestUtils.setField(service, "localLease", System.nanoTime() + TimeUnit.SECONDS.toNanos(14));
        var runtime = runtime(service);
        ReflectionTestUtils.setField(runtime, "running", true);
        ReflectionTestUtils.setField(runtime, "permitUntil", System.nanoTime() - TimeUnit.SECONDS.toNanos(2));

        assertThat(runtime.permitted()).isFalse();
        assertThat(appender.list).isEmpty();
        assertThat(service.available()).isFalse();
        assertThat(service.isFailed()).isFalse();
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains(
                "event=chat_runtime_first_denied", "running=true", "participation_failed=false",
                "blocked=false", "stopped=false", "permit_expired=true", "permit_remaining_ms=-",
                "last_check_start_age_ms=-1", "last_check_complete_age_ms=-1", "check_in_progress=false");
        assertThat(appender.list.getLast().getFormattedMessage()).contains(
                "event=chat_participation_first_unavailable", "reason=runtime_permit")
                .containsPattern("local_lease_remaining_ms=[1-9][0-9]*");

        service.heartbeat();
        assertThat(service.isFailed()).isTrue();
        verify(sockets).closeAll(1013, "CHAT_SERVICE_UNAVAILABLE");
        assertThat(appender.list).hasSize(3);
        assertThat(appender.list.getLast().getFormattedMessage()).contains("stage=heartbeat_unavailable");
    }

    @Test
    void blockedRuntimeWithAnUnexpiredPermitIsDistinguishedAndDoesNotSpamLogs() {
        var service = service(mock(PlatformTransactionManager.class), mock(ChatSocketSessions.class));
        var runtime = runtime(service);
        ReflectionTestUtils.setField(runtime, "running", true);
        ReflectionTestUtils.setField(runtime, "permitUntil", System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
        assertThat(runtime.permitted()).isTrue();
        ((AtomicBoolean) ReflectionTestUtils.getField(runtime, "blocked")).set(true);
        for (int i = 0; i < 10; i++) {
            assertThat(service.available()).isFalse();
        }
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains(
                "blocked=true", "permit_expired=false", "participation_failed=false");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void transportAndLocalLeaseFailuresAreDistinctAndEarlierGuardsStillShortCircuit() {
        var transport = service(mock(PlatformTransactionManager.class), mock(ChatSocketSessions.class));
        transport.setMessageTransportReady(() -> false);
        ReflectionTestUtils.setField(transport, "localLease", System.nanoTime() + TimeUnit.SECONDS.toNanos(14));
        assertThat(transport.available()).isFalse();
        assertThat(appender.list.getLast().getFormattedMessage()).contains("reason=message_transport");

        var lease = service(mock(PlatformTransactionManager.class), mock(ChatSocketSessions.class));
        ReflectionTestUtils.setField(lease, "localLease", System.nanoTime() - TimeUnit.SECONDS.toNanos(2));
        assertThat(lease.available()).isFalse();
        assertThat(appender.list.getLast().getFormattedMessage()).contains("reason=local_lease_expired");

        var draining = service(mock(PlatformTransactionManager.class), mock(ChatSocketSessions.class));
        draining.setRuntimePermit(() -> {
            throw new AssertionError("later guard must not execute");
        });
        draining.beginDrain();
        assertThat(draining.available()).isFalse();
        assertThat(appender.list.getLast().getFormattedMessage()).contains("reason=draining");
    }

    private ChatRuntimeLifecycle runtime(ChatParticipationService service) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        return new ChatRuntimeLifecycle(jdbc, service, mock(ChatRedisConnection.class), mock(ChatRedisChannels.class),
                mock(ChatSocketSessions.class), mock(ChatRuntimeMetrics.class), "diagnostic-test",
                Duration.ofSeconds(1));
    }

    @Test
    void logsOriginalTransactionFailureBeforeReturningTheSameSanitizedException() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenThrow(new DataAccessResourceFailureException("sql-password-canary",
                        new SQLException("raw-query-canary", "08001", 1040)));
        var service = service(manager, mock(ChatSocketSessions.class));
        ReflectionTestUtils.setField(service, "localLease", System.nanoTime() + TimeUnit.SECONDS.toNanos(14));
        var claims = new LocationResolutionClaims(7L, 41L, "41", 39L, "11680", Instant.now().plusSeconds(60));

        var failure = assertThrows(ChatParticipationUnavailableException.class,
                () -> service.join(7L, "unused-synthetic-sid", 23L, claims));
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).isEqualTo("chat participation service unavailable");
        assertThat(service.isFailed()).isFalse();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("event=chat_participation_first_transaction_failure", "stage=transaction_start",
                        "sql_state=08001", "sql_code=1040")
                .doesNotContain("sql-password-canary", "raw-query-canary", "unused-synthetic-sid");
    }

    private ChatParticipationService service(PlatformTransactionManager manager, ChatSocketSessions sockets) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        return new ChatParticipationService(jdbc, manager, mock(ChatViewStorageService.class),
                mock(ChatSessionAccessPolicy.class), mock(ChatParticipationProjection.class),
                mock(ApplicationEventPublisher.class), sockets, "diagnostic-test");
    }
}
