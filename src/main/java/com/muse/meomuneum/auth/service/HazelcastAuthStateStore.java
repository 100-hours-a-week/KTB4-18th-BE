package com.muse.meomuneum.auth.service;

import java.io.Serializable;
import java.time.Instant;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.queue.AuthQueueDiagnostics;

@Service
public class HazelcastAuthStateStore {
    private final HazelcastLoginQueueStateStore delegate;
    protected static final long OPERATION_CLAIM_LEASE_SECONDS = 30;

    public HazelcastAuthStateStore(ObjectProvider<HazelcastInstance> hazelcastProvider,
            AuthStateProperties properties) {
        this.delegate = new HazelcastLoginQueueStateStore(hazelcastProvider, properties,
                new HazelcastLoginQueueStateStore.FacadeHooks() {
                    @Override
                    public void assertLoginClaim(String ticketId, String operationId, String claimId, Long userId) {
                        HazelcastAuthStateStore.this.delegate.assertLoginClaim(ticketId, operationId, claimId, userId);
                    }

                    @Override
                    public void indexLoginSession(HazelcastInstance hz, String loginOperationId, String sid,
                            Instant refreshExpiresAt) {
                        HazelcastAuthStateStore.this.indexLoginSession(hz, loginOperationId, sid, refreshExpiresAt);
                    }

                    @Override
                    public void promoteUserSlot(Long userId, String sid) {
                        HazelcastAuthStateStore.this.promoteUserSlot(userId, sid);
                    }

                    @Override
                    public String hash(String value) {
                        return HazelcastAuthStateStore.this.delegate.hmac(value);
                    }

                    @Override
                    public void restoreSessionCount(IMap<String, AuthSession> sessions) {
                        HazelcastAuthStateStore.this.restoreSessionCount(sessions);
                    }

                    @Override
                    public void beforePendingCreateCas(String operationId) {
                        HazelcastAuthStateStore.this.beforePendingCreateCas(operationId);
                    }

                    @Override
                    public void beforeCreateQueueOffer(String operationId) {
                        HazelcastAuthStateStore.this.beforeCreateQueueOffer(operationId);
                    }

                    @Override
                    public boolean replaceCreateGeneration(IMap<String, Ticket> tickets, Ticket expected,
                            Ticket rotated) {
                        return HazelcastAuthStateStore.this.replaceCreateGeneration(tickets, expected, rotated);
                    }

                    @Override
                    public boolean replaceTicketState(IMap<String, Ticket> tickets, Ticket current, Ticket next) {
                        return HazelcastAuthStateStore.this.replaceTicketState(tickets, current, next);
                    }

                    @Override
                    public boolean offerQueueToken(QueueToken token) {
                        return HazelcastAuthStateStore.this.offerQueueToken(token);
                    }

                    @Override
                    public void clearAppendFence(QueueToken token) {
                        HazelcastAuthStateStore.this.clearAppendFence(token);
                    }

                    @Override
                    public List<Map.Entry<String, CreateOperation>> boundedCreateOperations(
                            IMap<String, CreateOperation> map) {
                        return HazelcastAuthStateStore.this.boundedCreateOperations(map);
                    }

                    @Override
                    public <K, V> List<Map.Entry<K, V>> boundedEntries(IMap<K, V> map, int limit) {
                        return HazelcastAuthStateStore.this.boundedEntries(map, limit);
                    }

                    @Override
                    public <K, V> List<V> boundedValues(IMap<K, V> map, int limit) {
                        return HazelcastAuthStateStore.this.boundedValues(map, limit);
                    }
                });
    }

    @Autowired(required = false)
    void setAuthQueueDiagnostics(AuthQueueDiagnostics diagnostics) {
        delegate.setDiagnostics(diagnostics);
    }

    @Value("${auth.queue.reserve-permit-on-ready:false}")
    void setReservePermitOnReady(boolean reservePermitOnReady) {
        delegate.setReservePermitOnReady(reservePermitOnReady);
    }

    public String diagnosticCorrelationId(String ticketId) {
        return delegate.diagnosticCorrelationId(ticketId);
    }

    public void diagnosticEvent(String event, String ticketId, String status) {
        delegate.diagnosticEvent(event, ticketId, status);
    }

    public void diagnosticLoginStage(String stage, String ticketId, String outcome, String errorCode,
            long durationMillis, Throwable failure) {
        delegate.diagnosticLoginStage(stage, ticketId, outcome, errorCode, durationMillis, failure);
    }

    public void diagnosticLoginRequestStage(String stage, String correlationId, String outcome,
            String errorCode, long durationMillis, Throwable failure) {
        delegate.diagnosticLoginRequestStage(stage, correlationId, outcome, errorCode, durationMillis, failure);
    }

    public AuthStatePermit acquireRequestPermit() {
        return delegate.acquireRequestPermit();
    }

    public void enforceTicketCreateRateLimit(String clientAddress) {
        delegate.enforceTicketCreateRateLimit(clientAddress);
    }

    public CreateTicket createOrReuseTicket(String suppliedCookie, String idempotencyKey) {
        return delegate.createOrReuseTicket(suppliedCookie, idempotencyKey);
    }

    public int processPendingCreateBatch(int maxItems) {
        return delegate.processPendingCreateBatch(maxItems);
    }

    public TicketView currentTicket(String queueCookie) {
        return delegate.currentTicket(queueCookie);
    }

    public void cancelCurrentTicket(String queueCookie) {
        delegate.cancelCurrentTicket(queueCookie);
    }

    public void cancelTicketById(String ticketId) {
        delegate.cancelTicketById(ticketId);
    }

    public Ticket claimReadyTicket(String queueCookie, String loginOperationId) {
        return delegate.claimReadyTicket(queueCookie, loginOperationId);
    }

    public Ticket claimReadyTicket(String queueCookie, String loginOperationId, String clientCorrelationId) {
        return delegate.claimReadyTicket(queueCookie, loginOperationId, clientCorrelationId);
    }

    public Ticket claimReadyTicket(String queueCookie, String loginOperationId, String clientCorrelationId,
            String requestedIdentityHash) {
        return delegate.claimReadyTicket(queueCookie, loginOperationId, clientCorrelationId, requestedIdentityHash);
    }

    public void completeTicket(Ticket ticket, LoginOutcome outcome, String sid) {
        delegate.completeTicket(ticket, outcome, sid);
    }

    public String loginIdentityHash(String email) {
        return delegate.loginIdentityHash(email);
    }

    public Ticket bindAuthenticationIdentity(Ticket claim, String identityHash) {
        return delegate.bindAuthenticationIdentity(claim, identityHash);
    }

    public Ticket bindAuthenticatedUser(Ticket claim, Long userId) {
        return delegate.bindAuthenticatedUser(claim, userId);
    }

    public Ticket markInvalidCredentials(Ticket claim) {
        return delegate.markInvalidCredentials(claim);
    }

    public void reconcileFailedLogin(Ticket claim) {
        delegate.reconcileFailedLogin(claim);
    }

    public void reconcileFailedLogin(Ticket claim, LoginOutcome observedOutcome) {
        delegate.reconcileFailedLogin(claim, observedOutcome);
    }

    public AuthSession loginRecoverySession(String queueCookie, String operationId) {
        return delegate.loginRecoverySession(queueCookie, operationId);
    }

    public void reconcileVerifyingTickets() {
        delegate.reconcileVerifyingTickets();
    }

    public void reconcileVerifyingTickets(ReconciliationAccumulator observations) {
        delegate.reconcileVerifyingTickets(observations);
    }

    public Optional<String> reserveBcryptPermit(String ticketId) {
        return delegate.reserveBcryptPermit(ticketId);
    }

    public boolean renewBcryptPermit(String owner, String key) {
        return delegate.renewBcryptPermit(owner, key);
    }

    public void releaseBcryptPermit(String owner, String key) {
        delegate.releaseBcryptPermit(owner, key);
    }

    public boolean ownsBcryptPermit(String owner, String key) {
        return delegate.ownsBcryptPermit(owner, key);
    }

    public void releaseUnclaimedBcryptPermit(String owner, String key) {
        delegate.releaseUnclaimedBcryptPermit(owner, key);
    }

    public void renewLocalBcryptPermits() {
        delegate.renewLocalBcryptPermits();
    }

    public Optional<Ticket> nextWaitingTicket() {
        return delegate.nextWaitingTicket();
    }

    public boolean admit(Ticket ticket) {
        return delegate.admit(ticket);
    }

    public boolean admit(Ticket ticket, String ignoredPermitKey) {
        return delegate.admit(ticket, ignoredPermitKey);
    }

    public Integer estimatedUsersAhead(Ticket ticket) {
        return delegate.estimatedUsersAhead(ticket);
    }

    public void refreshQueuePositionSnapshot() {
        delegate.refreshQueuePositionSnapshot();
    }

    public synchronized void restoreActiveTicketReservations() {
        delegate.restoreActiveTicketReservations();
    }

    public AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt) {
        return delegate.createSession(userId, sid, currentJti, refreshHash, loginOperationId,
                encryptedRefreshToken, accessExpiresAt, refreshExpiresAt);
    }

    public synchronized AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt, String userAgentSummary) {
        return delegate.createSession(userId, sid, currentJti, refreshHash, loginOperationId,
                encryptedRefreshToken, accessExpiresAt, refreshExpiresAt, userAgentSummary);
    }

    public synchronized AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt, String userAgentSummary, String ticketId, String ownerClaimId,
            Instant provisioningLeaseUntil) {
        return delegate.createSession(userId, sid, currentJti, refreshHash, loginOperationId,
                encryptedRefreshToken, accessExpiresAt, refreshExpiresAt, userAgentSummary, ticketId,
                ownerClaimId, provisioningLeaseUntil);
    }

    protected void indexLoginSession(HazelcastInstance hz, String loginOperationId, String sid,
            Instant refreshExpiresAt) {
        delegate.doIndexLoginSession(hz, loginOperationId, sid, refreshExpiresAt);
    }

    protected void promoteUserSlot(Long userId, String sid) {
        delegate.doPromoteUserSlot(userId, sid);
    }

    protected void restoreSessionCount(IMap<String, AuthSession> sessions) {
        delegate.doRestoreSessionCount(sessions);
    }

    void beforePendingCreateCas(String operationId) {
    }

    void beforeCreateQueueOffer(String operationId) {
    }

    boolean replaceCreateGeneration(IMap<String, Ticket> tickets, Ticket expected, Ticket rotated) {
        return delegate.doReplaceCreateGeneration(tickets, expected, rotated);
    }

    boolean replaceTicketState(IMap<String, Ticket> tickets, Ticket current, Ticket next) {
        return delegate.doReplaceTicketState(tickets, current, next);
    }

    boolean offerQueueToken(QueueToken token) {
        return delegate.doOfferQueueToken(token);
    }

    void clearAppendFence(QueueToken token) {
        delegate.doClearAppendFence(token);
    }

    void requeueReadyTicket(IMap<String, Ticket> tickets, Ticket ticket) {
        delegate.requeueReadyTicket(tickets, ticket);
    }

    void expireTicket(IMap<String, Ticket> tickets, Ticket ticket) {
        delegate.expireTicket(tickets, ticket);
    }

    List<Map.Entry<String, CreateOperation>> boundedCreateOperations(IMap<String, CreateOperation> map) {
        return delegate.doBoundedCreateOperations(map);
    }

    <K, V> List<Map.Entry<K, V>> boundedEntries(IMap<K, V> map, int limit) {
        return delegate.boundedEntries(map, limit);
    }

    <K, V> List<V> boundedValues(IMap<K, V> map, int limit) {
        return delegate.boundedValues(map, limit);
    }

    public long createRecoveryEntriesScanned() {
        return delegate.createRecoveryEntriesScanned();
    }

    public long createRecoveryTicketsRecovered() {
        return delegate.createRecoveryTicketsRecovered();
    }

    public long createRecoveryTicketsRetried() {
        return delegate.createRecoveryTicketsRetried();
    }

    public long oldestPendingAgeSeconds() {
        return delegate.oldestPendingAgeSeconds();
    }

    long oldestPendingAge(List<Map.Entry<String, CreateOperation>> operations, IMap<String, Ticket> tickets,
            Instant now) {
        return delegate.oldestPendingAge(operations, tickets, now);
    }

    int activeTicketCount() {
        return delegate.activeTicketCount();
    }

    public AuthSession getSession(String sid) {
        return delegate.getSession(sid);
    }

    public boolean replaceSession(AuthSession expected, AuthSession updated) {
        return delegate.replaceSession(expected, updated);
    }

    public AuthSession revokeSession(String sid, Long expectedUserId) {
        return delegate.revokeSession(sid, expectedUserId);
    }

    public List<AuthSession> activeSessions(Long userId) {
        return delegate.activeSessions(userId);
    }

    public String tokenHash(String token) {
        return delegate.tokenHash(token);
    }

    public String jtiHash(String jti) {
        return delegate.jtiHash(jti);
    }

    public Instant newAbsoluteSessionExpiry() {
        return delegate.newAbsoluteSessionExpiry();
    }

    public Instant nextRefreshIdleExpiry(Instant absoluteExpiry) {
        return delegate.nextRefreshIdleExpiry(absoluteExpiry);
    }

    public enum ReconciliationOutcome {
        SUCCESS("success"), MISS("miss"), ERROR("error"), OFFERED("offered"), QUEUE_FULL("queue_full"), APPEND_FENCED(
                "append_fenced");

        private final String metricValue;

        ReconciliationOutcome(String metricValue) {
            this.metricValue = metricValue;
        }

        public String metricValue() {
            return metricValue;
        }
    }

    public enum ReconciliationStage {
        RETRYABLE_REQUEUE("retryable_requeue"), CLAIM_OFFERING("claim_offering"), OFFER("offer"), OFFER_WAITING(
                "offer_waiting"), OFFER_RETRY("offer_retry"), OFFER_UNKNOWN("offer_unknown"), OFFERING_UNKNOWN(
                        "offering_unknown"), UNKNOWN_WAITING(
                                "unknown_waiting"), APPEND_FENCE_CLEAR("append_fence_clear"), SCHEDULER("scheduler");

        private final String metricValue;

        ReconciliationStage(String metricValue) {
            this.metricValue = metricValue;
        }

        public String metricValue() {
            return metricValue;
        }
    }

    public static final class ReconciliationAccumulator {
        private static final int MAX_CAUSE_DEPTH = 16;
        private static final int MAX_SUPPRESSED_PER_NODE = 8;
        private static final int MAX_STACK_FRAMES = 64;

        private long retryableScanned;
        private long expiredClaimsScanned;
        private long expiredClaimsRecovered;
        private long retryableRequeueSuccess;
        private long retryableRequeueMiss;
        private long retryableRequeueError;
        private long totalErrors;
        private final EnumMap<ReconciliationOutcome, Long> offers = new EnumMap<>(ReconciliationOutcome.class);
        private final EnumMap<ReconciliationStage, EnumMap<ReconciliationOutcome, Long>> transitions = new EnumMap<>(
                ReconciliationStage.class);
        private final EnumMap<ReconciliationStage, Long> suppressedDiagnostics = new EnumMap<>(
                ReconciliationStage.class);
        private final EnumMap<ReconciliationStage, SanitizedThrowable> diagnostics = new EnumMap<>(
                ReconciliationStage.class);

        public void retryableScanned() {
            retryableScanned++;
        }

        public void expiredClaimScanned() {
            expiredClaimsScanned++;
        }
        public void expiredClaimRecovered() {
            expiredClaimsRecovered++;
        }

        public void retryableRequeue(ReconciliationOutcome outcome) {
            switch (outcome) {
                case SUCCESS -> retryableRequeueSuccess++;
                case MISS -> retryableRequeueMiss++;
                case ERROR -> retryableRequeueError++;
                default -> throw new IllegalArgumentException("Invalid retryable CAS outcome");
            }
        }

        public void offer(ReconciliationOutcome outcome) {
            offers.merge(outcome, 1L, Long::sum);
        }

        public void transition(ReconciliationStage stage, ReconciliationOutcome outcome) {
            transitions.computeIfAbsent(stage, ignored -> new EnumMap<>(ReconciliationOutcome.class))
                    .merge(outcome, 1L, Long::sum);
        }

        public void diagnostic(ReconciliationStage stage, Throwable failure) {
            totalErrors++;
            if (!diagnostics.containsKey(stage)) {
                diagnostics.put(stage, sanitize(failure));
            } else {
                suppressedDiagnostics.merge(stage, 1L, Long::sum);
            }
        }

        public long retryableScannedCount() {
            return retryableScanned;
        }
        public long expiredClaimsScannedCount() {
            return expiredClaimsScanned;
        }
        public long expiredClaimsRecoveredCount() {
            return expiredClaimsRecovered;
        }
        public long retryableRequeueCount(ReconciliationOutcome outcome) {
            return switch (outcome) {
                case SUCCESS -> retryableRequeueSuccess;
                case MISS -> retryableRequeueMiss;
                case ERROR -> retryableRequeueError;
                default -> 0;
            };
        }
        public long offerCount(ReconciliationOutcome outcome) {
            return offers.getOrDefault(outcome, 0L);
        }
        public long transitionCount(ReconciliationStage stage, ReconciliationOutcome outcome) {
            return transitions.getOrDefault(stage, new EnumMap<>(ReconciliationOutcome.class))
                    .getOrDefault(outcome, 0L);
        }
        public long suppressedDiagnosticCount(ReconciliationStage stage) {
            return suppressedDiagnostics.getOrDefault(stage, 0L);
        }
        public Map<ReconciliationStage, SanitizedThrowable> diagnostics() {
            return Map.copyOf(diagnostics);
        }
        public boolean hasReconciliationActivity() {
            return retryableScanned > 0 || expiredClaimsScanned > 0 || !diagnostics.isEmpty();
        }
        public long totalSuppressedDiagnostics() {
            return suppressedDiagnostics.values().stream().mapToLong(Long::longValue).sum();
        }
        public long totalErrors() {
            return totalErrors;
        }

        private static SanitizedThrowable sanitize(Throwable failure) {
            return sanitize(failure, new IdentityHashMap<>(), 0);
        }

        private static SanitizedThrowable sanitize(Throwable failure, IdentityHashMap<Throwable, Boolean> seen,
                int depth) {
            if (failure == null) {
                return null;
            }
            if (depth >= MAX_CAUSE_DEPTH) {
                return new SanitizedThrowable(failure.getClass().getName(), List.of(), null, List.of(), 1);
            }
            if (seen.put(failure, Boolean.TRUE) != null) {
                return new SanitizedThrowable(failure.getClass().getName(), List.of(), null, List.of(), 1);
            }
            StackTraceElement[] stack = failure.getStackTrace();
            List<String> locations = java.util.Arrays.stream(stack).limit(MAX_STACK_FRAMES)
                    .map(frame -> frame.getClassName() + "." + frame.getMethodName() + "("
                            + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                    .toList();
            int truncatedCount = Math.max(0, stack.length - MAX_STACK_FRAMES);
            Throwable cause = failure.getCause();
            SanitizedThrowable safeCause = null;
            if (cause != null) {
                if (depth + 1 >= MAX_CAUSE_DEPTH) {
                    safeCause = new SanitizedThrowable(cause.getClass().getName(), List.of(), null, List.of(), 1);
                    truncatedCount++;
                } else {
                    safeCause = sanitize(cause, seen, depth + 1);
                }
            }
            Throwable[] suppressed = failure.getSuppressed();
            List<SanitizedThrowable> safeSuppressed = new java.util.ArrayList<>();
            int count = Math.min(suppressed.length, MAX_SUPPRESSED_PER_NODE);
            for (int index = 0; index < count; index++) {
                safeSuppressed.add(sanitize(suppressed[index], seen, depth + 1));
            }
            truncatedCount += Math.max(0, suppressed.length - MAX_SUPPRESSED_PER_NODE);
            truncatedCount += safeSuppressed.stream().mapToInt(SanitizedThrowable::truncatedCount).sum();
            if (safeCause != null) {
                truncatedCount += safeCause.truncatedCount();
            }
            return new SanitizedThrowable(failure.getClass().getName(), locations, safeCause,
                    List.copyOf(safeSuppressed), truncatedCount);
        }
    }

    public record SanitizedThrowable(String className, List<String> stackLocations, SanitizedThrowable cause,
            List<SanitizedThrowable> suppressed, int truncatedCount) {
    }

    public record AuthStatePermit(Semaphore semaphore) implements AutoCloseable {
        @Override
        public void close() {
            semaphore.release();
        }
    }

    public enum TicketStatus {
        CLAIMED, PENDING, OFFERING, CREATING, WAITING, ADMITTING, READY, // Queue lifecycle
        VERIFYING, RETRYABLE, REQUEUING, REQUEUE_OFFERING, REQUEUING_UNKNOWN, // Login claim lifecycle
        CONSUMED, CANCELLED, EXPIRED // Terminal states
    }

    public enum LoginOutcome {
        SUCCESS, INVALID_CREDENTIALS
    }

    public record TicketView(TicketStatus status, LoginOutcome loginOutcome, Integer usersAhead,
            int nextPollSeconds, String ticketId) {
    }

    public record Ticket(String ticketId, long order, Instant createdAt, Instant expiresAt,
            Instant readyExpiresAt, TicketStatus status, LoginOutcome loginOutcome, String createOperationId,
            String loginOperationId, String claimId, Instant claimLeaseUntil, String bcryptPermitKey, String sid,
            long enqueueGeneration, String authPhase, String authIdentityHash, Long authenticatedUserId)
            implements
                Serializable {
        private static final long serialVersionUID = 1L;

        public Ticket(String ticketId, long order, Instant createdAt, Instant expiresAt,
                Instant readyExpiresAt, TicketStatus status, LoginOutcome loginOutcome, String createOperationId,
                String loginOperationId, String claimId, Instant claimLeaseUntil, String bcryptPermitKey, String sid,
                long enqueueGeneration) {
            this(ticketId, order, createdAt, expiresAt, readyExpiresAt, status, loginOutcome, createOperationId,
                    loginOperationId, claimId, claimLeaseUntil, bcryptPermitKey, sid, enqueueGeneration,
                    null, null, null);
        }

        Ticket withState(TicketStatus next, LoginOutcome outcome, String operationId, String claim,
                Instant leaseUntil, Instant readyDeadline, String permitKey, String sessionId) {
            return new Ticket(ticketId, order, createdAt, expiresAt, readyDeadline, next, outcome,
                    createOperationId, operationId == null ? loginOperationId : operationId, claim, leaseUntil,
                    permitKey, sessionId == null ? sid : sessionId, enqueueGeneration,
                    authPhase, authIdentityHash, authenticatedUserId);
        }

        Ticket withGeneration(long generation, TicketStatus next, String permitKey) {
            return new Ticket(ticketId, order, createdAt, expiresAt, readyExpiresAt, next, loginOutcome,
                    createOperationId, loginOperationId, claimId, claimLeaseUntil, permitKey, sid, generation,
                    authPhase, authIdentityHash, authenticatedUserId);
        }

        Ticket withEnqueue(long nextOrder, long generation, TicketStatus next, String permitKey) {
            return new Ticket(ticketId, nextOrder, createdAt, expiresAt, readyExpiresAt, next, loginOutcome,
                    createOperationId, loginOperationId, claimId, claimLeaseUntil, permitKey, sid, generation,
                    authPhase, authIdentityHash, authenticatedUserId);
        }

        Ticket withAuthentication(String phase, String identityHash, Long userId) {
            return new Ticket(ticketId, order, createdAt, expiresAt, readyExpiresAt, status, loginOutcome,
                    createOperationId, loginOperationId, claimId, claimLeaseUntil, bcryptPermitKey, sid,
                    enqueueGeneration, phase, identityHash, userId);
        }

        Ticket withLoginClaim(String operationId, String nextClaimId, Instant leaseUntil, String permitKey,
                String sessionId) {
            return new Ticket(ticketId, order, createdAt, expiresAt, readyExpiresAt, TicketStatus.VERIFYING,
                    null, createOperationId, operationId, nextClaimId, leaseUntil, permitKey, sessionId,
                    enqueueGeneration, authPhase, authIdentityHash, authenticatedUserId);
        }
    }

    public record CreateTicket(String queueCookie, TicketView view, boolean newlyCreated) {
    }

    public enum CreateOperationStatus {
        CLAIMED, PENDING, OFFERING, ACCEPTED, TERMINAL
    }

    public record CreateOperation(CreateOperationStatus status, String ticketId, String cookieHash,
            String ownerToken, String reservationId, Instant leaseUntil, Instant lastRequestedAt)
            implements
                Serializable {
        private static final long serialVersionUID = 1L;

        static CreateOperation claimed(String ticketId, String cookieHash, String ownerToken,
                Instant now, Instant leaseUntil) {
            return new CreateOperation(CreateOperationStatus.CLAIMED, ticketId, cookieHash, ownerToken,
                    null, leaseUntil, now);
        }

        static CreateOperation accepted(String ticketId, String cookieHash, Instant now) {
            return new CreateOperation(CreateOperationStatus.ACCEPTED, ticketId, cookieHash,
                    null, ticketId, null, now);
        }

        CreateOperation withReservation(String id, String reservation, Instant now) {
            return new CreateOperation(CreateOperationStatus.PENDING, id, cookieHash, ownerToken,
                    reservation, leaseUntil, now);
        }

        CreateOperation withTakeover(String nextOwnerToken, String nextReservationId, Instant nextLeaseUntil,
                Instant now) {
            return new CreateOperation(CreateOperationStatus.CLAIMED, ticketId, cookieHash, nextOwnerToken,
                    nextReservationId, nextLeaseUntil, now);
        }

        CreateOperation withStatus(CreateOperationStatus next, Instant now) {
            return new CreateOperation(next, ticketId, cookieHash, ownerToken,
                    next == CreateOperationStatus.PENDING && isRetryMarker(reservationId)
                            ? reservationId
                            : next == CreateOperationStatus.OFFERING || next == CreateOperationStatus.PENDING
                                    ? null
                                    : reservationId,
                    next == CreateOperationStatus.OFFERING
                            ? now.plusSeconds(OPERATION_CLAIM_LEASE_SECONDS)
                            : leaseUntil,
                    now);
        }

        CreateOperation withRetryablePending(Instant now) {
            String retryMarker = isRetryMarker(reservationId) ? reservationId : null;
            return new CreateOperation(CreateOperationStatus.PENDING, ticketId, cookieHash, ownerToken,
                    retryMarker, leaseUntil, now);
        }

        private static boolean isRetryMarker(String value) {
            return value != null && (value.startsWith("retry-same-generation:")
                    || value.startsWith("retry-generation:"));
        }

        CreateOperation withOffering(Instant now) {
            return new CreateOperation(CreateOperationStatus.OFFERING, ticketId, cookieHash, ownerToken,
                    null, now.plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), now);
        }

        CreateOperation withRetryMarker(long targetGeneration, Instant now) {
            return new CreateOperation(CreateOperationStatus.OFFERING, ticketId, cookieHash, ownerToken,
                    "retry-generation:" + targetGeneration, now.plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), now);
        }

        CreateOperation withSameGenerationRetryMarker(long generation, Instant now) {
            return new CreateOperation(CreateOperationStatus.OFFERING, ticketId, cookieHash, ownerToken,
                    "retry-same-generation:" + generation, now.plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), now);
        }

        CreateOperation withLastRequestedAt(Instant now) {
            return new CreateOperation(status, ticketId, cookieHash, ownerToken,
                    reservationId, leaseUntil, now);
        }
    }

    public record QueueToken(String ticketId, long generation) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record QueueOfferFence(QueueToken token, Instant deadline) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    static final class AppendFencedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public record PermitLease(String owner, Instant expiresAt) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public enum SessionStatus {
        PROVISIONING, ACTIVE, REVOKED
    }

    public enum SlotStatus {
        PENDING, ACTIVE
    }

    public record IndexSlot(SlotStatus status, Instant leaseUntil) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record UserSessionIndex(Map<String, IndexSlot> sessions) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record AuthSession(Long userId, String sid, String userAgentSummary, SessionStatus status,
            String currentJtiHash,
            String currentRefreshHash, String previousJtiHash, String previousRefreshHash,
            String lastRefreshOperationId, Instant replayUntil, String encryptedReplayRefreshToken,
            String loginOperationId, Instant loginRecoveryUntil, long loginRecoveryGeneration,
            String loginRecoveryRefreshHash, String encryptedLoginRecoveryRefreshToken,
            long generation, Instant createdAt, Instant lastRefreshedAt, Instant accessExpiresAt,
            Instant refreshIdleExpiresAt, Instant refreshAbsoluteExpiresAt, Instant revokedAt,
            String ticketId, String ownerClaimId, Instant provisioningLeaseUntil)
            implements
                Serializable {
        private static final long serialVersionUID = 1L;

        public AuthSession(Long userId, String sid, String userAgentSummary, SessionStatus status,
                String currentJtiHash, String currentRefreshHash, String previousJtiHash, String previousRefreshHash,
                String lastRefreshOperationId, Instant replayUntil, String encryptedReplayRefreshToken,
                String loginOperationId, Instant loginRecoveryUntil, long loginRecoveryGeneration,
                String loginRecoveryRefreshHash, String encryptedLoginRecoveryRefreshToken,
                long generation, Instant createdAt, Instant lastRefreshedAt, Instant accessExpiresAt,
                Instant refreshIdleExpiresAt, Instant refreshAbsoluteExpiresAt, Instant revokedAt) {
            this(userId, sid, userAgentSummary, status, currentJtiHash, currentRefreshHash, previousJtiHash,
                    previousRefreshHash, lastRefreshOperationId, replayUntil, encryptedReplayRefreshToken,
                    loginOperationId, loginRecoveryUntil, loginRecoveryGeneration, loginRecoveryRefreshHash,
                    encryptedLoginRecoveryRefreshToken, generation, createdAt, lastRefreshedAt, accessExpiresAt,
                    refreshIdleExpiresAt, refreshAbsoluteExpiresAt, revokedAt, null, null, null);
        }

        public AuthSession withRotation(String jti, String refreshHash, String oldJtiHash, String oldHash,
                String operationId, Instant retryUntil, String encryptedToken, Instant accessExpiry,
                Instant idleExpiry) {
            return new AuthSession(userId, sid, userAgentSummary, status, jti, refreshHash, oldJtiHash, oldHash,
                    operationId,
                    retryUntil, encryptedToken, loginOperationId, loginRecoveryUntil, generation + 1,
                    refreshHash, encryptedToken, generation + 1,
                    createdAt, Instant.now(), accessExpiry,
                    idleExpiry, refreshAbsoluteExpiresAt, revokedAt, ticketId, ownerClaimId,
                    provisioningLeaseUntil);
        }

        public AuthSession withCanonicalRefresh(String jti, String refreshHash, Instant refreshedAt,
                Instant idleExpiry, Instant absoluteExpiry) {
            return new AuthSession(userId, sid, userAgentSummary, SessionStatus.ACTIVE, jti, refreshHash,
                    null, null, null, null, null, null, null, 0, null, null, generation + 1,
                    createdAt, refreshedAt, accessExpiresAt, idleExpiry, absoluteExpiry, null,
                    ticketId, ownerClaimId, provisioningLeaseUntil);
        }

        public AuthSession revoked() {
            return new AuthSession(userId, sid, userAgentSummary, SessionStatus.REVOKED, currentJtiHash,
                    currentRefreshHash,
                    previousJtiHash, previousRefreshHash, lastRefreshOperationId, replayUntil,
                    null, loginOperationId, loginRecoveryUntil, loginRecoveryGeneration,
                    loginRecoveryRefreshHash, null, generation + 1,
                    createdAt, lastRefreshedAt, accessExpiresAt,
                    refreshIdleExpiresAt, refreshAbsoluteExpiresAt, Instant.now(), ticketId, ownerClaimId,
                    provisioningLeaseUntil);
        }

        public AuthSession withoutExpiredReplayMaterial(Instant now) {
            String replayCiphertext = replayUntil != null && !replayUntil.isAfter(now)
                    ? null
                    : encryptedReplayRefreshToken;
            String loginCiphertext = loginRecoveryUntil != null && !loginRecoveryUntil.isAfter(now)
                    ? null
                    : encryptedLoginRecoveryRefreshToken;
            if (replayCiphertext == encryptedReplayRefreshToken
                    && loginCiphertext == encryptedLoginRecoveryRefreshToken) {
                return this;
            }
            return new AuthSession(userId, sid, userAgentSummary, status, currentJtiHash, currentRefreshHash,
                    previousJtiHash, previousRefreshHash, lastRefreshOperationId, replayUntil,
                    replayCiphertext, loginOperationId, loginRecoveryUntil, loginRecoveryGeneration,
                    loginRecoveryRefreshHash, loginCiphertext, generation, createdAt, lastRefreshedAt,
                    accessExpiresAt, refreshIdleExpiresAt, refreshAbsoluteExpiresAt, revokedAt,
                    ticketId, ownerClaimId, provisioningLeaseUntil);
        }
    }
}
