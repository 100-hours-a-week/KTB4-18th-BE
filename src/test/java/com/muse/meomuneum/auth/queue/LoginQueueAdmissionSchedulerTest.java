package com.muse.meomuneum.auth.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LoginQueueAdmissionSchedulerTest {

    @Test
    void admitsAtMostOneHundredFiftyTicketsPerCycle() {
        HazelcastAuthStateStore store = mock(HazelcastAuthStateStore.class);
        var now = Instant.now();
        List<HazelcastAuthStateStore.Ticket> tickets = new ArrayList<>();
        for (int sequence = 1; sequence <= 151; sequence++) {
            tickets.add(new HazelcastAuthStateStore.Ticket(UUID.randomUUID().toString(), sequence, now,
                    now.plusSeconds(900), null, HazelcastAuthStateStore.TicketStatus.WAITING, null,
                    UUID.randomUUID().toString(), null, null, null, null, null, 1));
        }
        List<HazelcastAuthStateStore.Ticket> admitted = new ArrayList<>();
        int[] nextIndex = {0};
        when(store.nextWaitingTicket()).thenAnswer(invocation -> nextIndex[0] < tickets.size()
                ? Optional.of(tickets.get(nextIndex[0]))
                : Optional.empty());
        doAnswer(invocation -> {
            HazelcastAuthStateStore.Ticket ticket = invocation.getArgument(0);
            admitted.add(ticket);
            nextIndex[0]++;
            return true;
        }).when(store).admit(org.mockito.ArgumentMatchers.any());
        LoginQueueAdmissionScheduler scheduler = new LoginQueueAdmissionScheduler(store);

        scheduler.admitNextTicket();

        verify(store, times(150)).nextWaitingTicket();
        assertThat(admitted).containsExactlyElementsOf(tickets.subList(0, 150));
        assertThat(nextIndex[0]).isEqualTo(150);
        assertThat(store.nextWaitingTicket()).contains(tickets.get(150));

        scheduler.admitNextTicket();

        assertThat(admitted).containsExactlyElementsOf(tickets);
        assertThat(nextIndex[0]).isEqualTo(151);
    }

    @Test
    void reconciliationFailureIsCountedAndLaterPassUpdatesSuccessTelemetry() {
        HazelcastAuthStateStore store = mock(HazelcastAuthStateStore.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoginQueueAdmissionScheduler scheduler = new LoginQueueAdmissionScheduler(store, registry);
        doThrow(new IllegalStateException("sensitive details must not become a metric label"))
                .when(store).reconcileVerifyingTickets(org.mockito.ArgumentMatchers.any());

        scheduler.reconcileClaims();
        org.mockito.Mockito.reset(store);
        scheduler.reconcileClaims();

        assertThat(registry.get("auth.queue.recovery.runs").counter().count()).isEqualTo(2);
        assertThat(registry.get("auth.queue.recovery.failures").tag("outcome", "failure").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("auth.queue.recovery.batch_duration").timer().count()).isEqualTo(2);
        assertThat(registry.get("auth.queue.recovery.last_success_epoch_seconds").gauge().value()).isPositive();
        assertThat(registry.get("auth.queue.recovery.oldest_pending_age_seconds").gauge().value()).isZero();
    }

    @Test
    void reconciliationFlushesPartialInvocationObservationsOnceAfterFailure() {
        HazelcastAuthStateStore store = mock(HazelcastAuthStateStore.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoginQueueAdmissionScheduler scheduler = new LoginQueueAdmissionScheduler(store, registry);
        doAnswer(invocation -> {
            HazelcastAuthStateStore.ReconciliationAccumulator observations = invocation.getArgument(0);
            observations.retryableScanned();
            observations.retryableRequeue(HazelcastAuthStateStore.ReconciliationOutcome.SUCCESS);
            observations.offer(HazelcastAuthStateStore.ReconciliationOutcome.QUEUE_FULL);
            throw new IllegalStateException("SECRET_SENTINEL");
        }).when(store).reconcileVerifyingTickets(org.mockito.ArgumentMatchers.any());

        scheduler.reconcileClaims();

        assertThat(registry.get("auth.queue.reconcile.retryable.scanned").counter().count()).isEqualTo(1);
        assertThat(registry.get("auth.queue.reconcile.retryable_requeue_cas")
                .tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("auth.queue.reconcile.offer")
                .tag("outcome", "queue_full").counter().count()).isEqualTo(1);
        assertThat(registry.get("auth.queue.recovery.failures").tag("outcome", "failure").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("auth.queue.recovery.batch_duration").timer().count()).isEqualTo(1);
    }

    @Test
    void schedulerCapsSanitizedDiagnosticsPerStageAndResetsTheCapEachInvocation() {
        HazelcastAuthStateStore store = mock(HazelcastAuthStateStore.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoginQueueAdmissionScheduler scheduler = new LoginQueueAdmissionScheduler(store, registry);
        doAnswer(invocation -> {
            HazelcastAuthStateStore.ReconciliationAccumulator observations = invocation.getArgument(0);
            IllegalStateException failure = new IllegalStateException("SECRET_SENTINEL_OUTER",
                    new IllegalArgumentException("SECRET_SENTINEL_CAUSE"));
            observations.transition(HazelcastAuthStateStore.ReconciliationStage.OFFER,
                    HazelcastAuthStateStore.ReconciliationOutcome.ERROR);
            observations.diagnostic(HazelcastAuthStateStore.ReconciliationStage.OFFER, failure);
            observations.transition(HazelcastAuthStateStore.ReconciliationStage.OFFER,
                    HazelcastAuthStateStore.ReconciliationOutcome.ERROR);
            observations.diagnostic(HazelcastAuthStateStore.ReconciliationStage.OFFER,
                    new IllegalStateException("SECRET_SENTINEL_REPEAT"));
            throw failure;
        }).when(store).reconcileVerifyingTickets(org.mockito.ArgumentMatchers.any());

        Logger logger = (Logger) LoggerFactory.getLogger(LoginQueueAdmissionScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler.reconcileClaims();
            scheduler.reconcileClaims();
        } finally {
            logger.detachAppender(appender);
        }

        var diagnosticLogs = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("reconciliation diagnostic"))
                .toList();
        var summaries = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("reconciliation summary"))
                .toList();
        assertThat(diagnosticLogs).hasSize(4);
        assertThat(summaries).hasSize(2);
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("SECRET_SENTINEL");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(summaries).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("errors=3", "suppressedDiagnostics=1"));
        assertThat(registry.get("auth.queue.reconcile.transition")
                .tag("stage", "offer").tag("outcome", "error").counter().count()).isEqualTo(4);
        assertThat(registry.get("auth.queue.reconcile.errors.suppressed_logs")
                .tag("stage", "offer").counter().count()).isEqualTo(2);
    }
}
