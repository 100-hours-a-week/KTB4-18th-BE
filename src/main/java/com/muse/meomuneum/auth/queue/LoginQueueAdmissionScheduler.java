package com.muse.meomuneum.auth.queue;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hazelcast.core.HazelcastException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.ReconciliationAccumulator;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.ReconciliationOutcome;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.ReconciliationStage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Component
@ConditionalOnProperty(prefix = "auth.state.hazelcast", name = "enabled", havingValue = "true")
public class LoginQueueAdmissionScheduler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoginQueueAdmissionScheduler.class);
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 30_000;

    private static final int MAX_ADMISSION_BATCH = 150;
    private static final int MAX_CREATE_APPEND_BATCH = 20;

    private final HazelcastAuthStateStore store;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastRecoverySuccessEpochSeconds = new AtomicLong();
    private final AtomicLong reportedRecovered = new AtomicLong();
    private final AtomicLong reportedRetried = new AtomicLong();
    private final AtomicLong reportedEntriesScanned = new AtomicLong();
    private final AtomicLong nextAdmissionLogAt = new AtomicLong();
    private final AtomicLong nextPositionSnapshotLogAt = new AtomicLong();
    private final AtomicLong nextReconcileLogAt = new AtomicLong();
    private final AtomicLong nextPermitLogAt = new AtomicLong();
    private final AtomicLong nextCreateAppenderLogAt = new AtomicLong();
    private final Counter retryableScannedCounter;
    private final Counter expiredClaimScannedCounter;
    private final Counter expiredClaimRecoveredCounter;
    private final EnumMap<ReconciliationOutcome, Counter> retryableCasCounters = new EnumMap<>(
            ReconciliationOutcome.class);
    private final EnumMap<ReconciliationOutcome, Counter> offerCounters = new EnumMap<>(ReconciliationOutcome.class);
    private final Map<ReconciliationStage, Map<ReconciliationOutcome, Counter>> transitionCounters = new EnumMap<>(
            ReconciliationStage.class);
    private final EnumMap<ReconciliationStage, Counter> suppressedLogCounters = new EnumMap<>(
            ReconciliationStage.class);

    @Autowired
    public LoginQueueAdmissionScheduler(HazelcastAuthStateStore store, MeterRegistry meterRegistry) {
        this.store = store;
        this.meterRegistry = meterRegistry;
        this.retryableScannedCounter = Counter.builder("auth.queue.reconcile.retryable.scanned")
                .register(meterRegistry);
        this.expiredClaimScannedCounter = Counter.builder("auth.queue.reconcile.expired_claims.scanned")
                .register(meterRegistry);
        this.expiredClaimRecoveredCounter = Counter.builder("auth.queue.reconcile.expired_claims.recovered")
                .register(meterRegistry);
        for (ReconciliationOutcome outcome : new ReconciliationOutcome[]{
                ReconciliationOutcome.SUCCESS,
                ReconciliationOutcome.MISS,
                ReconciliationOutcome.ERROR}) {
            retryableCasCounters.put(outcome, Counter.builder("auth.queue.reconcile.retryable_requeue_cas")
                    .tag("outcome", outcome.metricValue()).register(meterRegistry));
        }
        for (ReconciliationOutcome outcome : new ReconciliationOutcome[]{
                ReconciliationOutcome.OFFERED,
                ReconciliationOutcome.QUEUE_FULL,
                ReconciliationOutcome.APPEND_FENCED,
                ReconciliationOutcome.ERROR}) {
            offerCounters.put(outcome, Counter.builder("auth.queue.reconcile.offer")
                    .tag("outcome", outcome.metricValue()).register(meterRegistry));
        }
        for (ReconciliationStage stage : ReconciliationStage.values()) {
            EnumMap<ReconciliationOutcome, Counter> byOutcome = new EnumMap<>(
                    ReconciliationOutcome.class);
            for (ReconciliationOutcome outcome : new ReconciliationOutcome[]{
                    ReconciliationOutcome.SUCCESS,
                    ReconciliationOutcome.MISS,
                    ReconciliationOutcome.ERROR}) {
                byOutcome.put(outcome, Counter.builder("auth.queue.reconcile.transition")
                        .tag("stage", stage.metricValue()).tag("outcome", outcome.metricValue())
                        .register(meterRegistry));
            }
            transitionCounters.put(stage, byOutcome);
            suppressedLogCounters.put(stage, Counter.builder("auth.queue.reconcile.errors.suppressed_logs")
                    .tag("stage", stage.metricValue()).register(meterRegistry));
        }
        registerRecoveryGauges();
    }

    public LoginQueueAdmissionScheduler(HazelcastAuthStateStore store) {
        this(store, new SimpleMeterRegistry());
    }

    @Override
    public void run(ApplicationArguments args) {
        store.restoreActiveTicketReservations();
    }

    @Scheduled(fixedDelayString = "${auth.queue.scheduler-interval:100ms}")
    public void admitNextTicket() {
        try {
            for (int admitted = 0; admitted < MAX_ADMISSION_BATCH; admitted++) {
                var waiting = store.nextWaitingTicket();
                if (waiting.isEmpty()) {
                    return;
                }
                store.admit(waiting.get());
            }
        } catch (AuthStateUnavailableException | HazelcastException failure) {
            logFailure(nextAdmissionLogAt, "admission", failure);
        }
    }

    @Scheduled(fixedDelayString = "${auth.queue.create-appender-interval:100ms}")
    public void appendPendingCreateTickets() {
        try {
            store.processPendingCreateBatch(MAX_CREATE_APPEND_BATCH);
        } catch (AuthStateUnavailableException | HazelcastException failure) {
            logFailure(nextCreateAppenderLogAt, "ticket create appender", failure);
        }
    }

    @Scheduled(fixedDelayString = "${auth.queue.reconcile-interval:1000ms}")
    public void reconcileClaims() {
        Timer.Sample timer = Timer.start(meterRegistry);
        ReconciliationAccumulator observations = new ReconciliationAccumulator();
        Counter.builder("auth.queue.recovery.runs").register(meterRegistry).increment();
        try {
            store.reconcileVerifyingTickets(observations);
            lastRecoverySuccessEpochSeconds.set(System.currentTimeMillis() / 1000L);
            incrementDelta("tickets_recovered", "success", reportedRecovered,
                    store.createRecoveryTicketsRecovered());
            incrementDelta("tickets_retried", "retry", reportedRetried, store.createRecoveryTicketsRetried());
            incrementDelta("entries_scanned", "success", reportedEntriesScanned,
                    store.createRecoveryEntriesScanned());
        } catch (RuntimeException failure) {
            reconcileCounter("failures", "failure").increment();
            observations.diagnostic(ReconciliationStage.SCHEDULER, failure);
        } finally {
            try {
                flushReconciliationObservations(observations);
            } catch (RuntimeException flushFailure) {
                log.warn("Auth queue reconciliation observation flush failed type={}",
                        flushFailure.getClass().getSimpleName());
            } finally {
                timer.stop(Timer.builder("auth.queue.recovery.batch_duration").register(meterRegistry));
            }
        }
    }

    private void flushReconciliationObservations(ReconciliationAccumulator observations) {
        increment(retryableScannedCounter, observations.retryableScannedCount());
        increment(expiredClaimScannedCounter, observations.expiredClaimsScannedCount());
        increment(expiredClaimRecoveredCounter, observations.expiredClaimsRecoveredCount());
        retryableCasCounters
                .forEach((outcome, counter) -> increment(counter, observations.retryableRequeueCount(outcome)));
        offerCounters.forEach((outcome, counter) -> increment(counter, observations.offerCount(outcome)));
        transitionCounters.forEach((stage, counters) -> counters
                .forEach((outcome, counter) -> increment(counter, observations.transitionCount(stage, outcome))));
        suppressedLogCounters
                .forEach((stage, counter) -> increment(counter, observations.suppressedDiagnosticCount(stage)));
        observations.diagnostics().forEach((stage, diagnostic) -> log
                .warn("Auth queue reconciliation diagnostic stage={} details={}", stage.metricValue(), diagnostic));
        if (observations.hasReconciliationActivity()) {
            log.info("Auth queue reconciliation summary expiredClaimScanned={} expiredClaimRecovered={} "
                    + "retryableScanned={} retryableCas={} offer={} transitions={} errors={} "
                    + "suppressedDiagnostics={}",
                    observations.expiredClaimsScannedCount(), observations.expiredClaimsRecoveredCount(),
                    observations.retryableScannedCount(), retryableCasSummary(observations),
                    offerSummary(observations), transitionSummary(observations), observations.totalErrors(),
                    observations.totalSuppressedDiagnostics());
        }
    }

    private void increment(Counter counter, long amount) {
        if (amount > 0) {
            counter.increment(amount);
        }
    }

    private String retryableCasSummary(ReconciliationAccumulator observations) {
        return "success=" + observations.retryableRequeueCount(ReconciliationOutcome.SUCCESS)
                + ",miss=" + observations.retryableRequeueCount(ReconciliationOutcome.MISS)
                + ",error=" + observations.retryableRequeueCount(ReconciliationOutcome.ERROR);
    }

    private String offerSummary(ReconciliationAccumulator observations) {
        return "offered=" + observations.offerCount(ReconciliationOutcome.OFFERED)
                + ",queue_full=" + observations.offerCount(ReconciliationOutcome.QUEUE_FULL)
                + ",append_fenced="
                + observations.offerCount(ReconciliationOutcome.APPEND_FENCED)
                + ",error=" + observations.offerCount(ReconciliationOutcome.ERROR);
    }

    private String transitionSummary(ReconciliationAccumulator observations) {
        StringBuilder summary = new StringBuilder();
        for (ReconciliationStage stage : ReconciliationStage.values()) {
            if (summary.length() > 0) {
                summary.append(';');
            }
            summary.append(stage.metricValue()).append("=success:")
                    .append(observations.transitionCount(stage, ReconciliationOutcome.SUCCESS))
                    .append(",miss:")
                    .append(observations.transitionCount(stage, ReconciliationOutcome.MISS))
                    .append(",error:")
                    .append(observations.transitionCount(stage, ReconciliationOutcome.ERROR));
        }
        return summary.toString();
    }

    private Counter recoveryCounter(String name, String outcome) {
        return Counter.builder("auth.queue.recovery." + name).tag("task", "create").tag("outcome", outcome)
                .register(meterRegistry);
    }

    private Counter reconcileCounter(String name, String outcome) {
        return Counter.builder("auth.queue.recovery." + name).tag("outcome", outcome).register(meterRegistry);
    }

    private void incrementDelta(String name, String outcome, AtomicLong reported, long total) {
        long previous = reported.getAndSet(total);
        if (total > previous) {
            recoveryCounter(name, outcome).increment(total - previous);
        }
    }

    private void registerRecoveryGauges() {
        meterRegistry.gauge("auth.queue.recovery.last_success_epoch_seconds", lastRecoverySuccessEpochSeconds);
        meterRegistry.gauge("auth.queue.recovery.oldest_pending_age_seconds", store,
                HazelcastAuthStateStore::oldestPendingAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${auth.bcrypt.permit-renew-interval:5s}")
    public void renewBcryptPermits() {
        try {
            store.renewLocalBcryptPermits();
        } catch (AuthStateUnavailableException | HazelcastException failure) {
            logFailure(nextPermitLogAt, "BCrypt permit renewal", failure);
        }
    }

    @Scheduled(fixedDelayString = "${auth.queue.position-snapshot-interval:10s}")
    public void refreshQueuePositionSnapshot() {
        try {
            store.refreshQueuePositionSnapshot();
        } catch (AuthStateUnavailableException | HazelcastException failure) {
            logFailure(nextPositionSnapshotLogAt, "queue position snapshot", failure);
        }
    }

    private void logFailure(AtomicLong nextLogAt, String operation, RuntimeException failure) {
        long now = System.currentTimeMillis();
        long next = nextLogAt.get();
        if (now >= next && nextLogAt.compareAndSet(next, now + FAILURE_LOG_INTERVAL_MILLIS)) {
            log.warn("Auth queue scheduled {} failed; shared state remains fail-closed and will retry",
                    operation, failure);
        }
    }
}
