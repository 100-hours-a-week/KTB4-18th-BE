package com.muse.meomuneum.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.ObjectProvider;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateOperationInProgressException;
import com.muse.meomuneum.auth.exception.AuthStateTooManyRequestsException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.queue.AuthQueueDiagnostics;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.AppendFencedException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.AuthSession;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.AuthStatePermit;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.CreateOperation;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.CreateOperationStatus;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.CreateTicket;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.LoginOutcome;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.QueueOfferFence;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.QueueToken;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.ReconciliationAccumulator;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.ReconciliationOutcome;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.ReconciliationStage;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.SessionStatus;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.Ticket;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.TicketStatus;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.TicketView;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.UserSessionIndex;

final class HazelcastLoginQueueStateStore {
    interface FacadeHooks extends HazelcastAuthSessionStore.Hooks {
        void restoreSessionCount(IMap<String, AuthSession> sessions);

        void beforePendingCreateCas(String operationId);

        void beforeCreateQueueOffer(String operationId);

        boolean replaceCreateGeneration(IMap<String, Ticket> tickets, Ticket expected, Ticket rotated);

        boolean replaceTicketState(IMap<String, Ticket> tickets, Ticket current, Ticket next);

        boolean offerQueueToken(QueueToken token);

        void clearAppendFence(QueueToken token);

        List<Map.Entry<String, CreateOperation>> boundedCreateOperations(IMap<String, CreateOperation> map);

        <K, V> List<Map.Entry<K, V>> boundedEntries(IMap<K, V> map, int limit);

        <K, V> List<V> boundedValues(IMap<K, V> map, int limit);
    }

    private static final String TICKETS = "login-queue-tickets-v2";
    private static final String TICKET_DIAGNOSTICS = "login-queue-ticket-diagnostics-v1";
    private static final String BINDINGS = "login-queue-cookie-bindings-v2";
    private static final String CREATE_OPS = "login-queue-create-operations-v2";
    private static final String ORDER = "login-queue-order-v2";
    private static final String SESSIONS = "auth-sessions-v2";
    private static final String LOGIN_SESSION_INDEX = "auth-login-session-index-v2";
    private static final String APPEND_FENCES = "login-queue-append-fences-v2";
    private static final String CANCEL_FENCES = "login-queue-ticket-cancel-fences-v1";
    private static final int RECOVERY_BATCH_SIZE = 100;
    private static final int QUEUE_POSITION_MAP_BATCH_SIZE = 100;
    private static final long QUEUE_POSITION_SNAPSHOT_TTL_SECONDS = 30;
    protected static final long OPERATION_CLAIM_LEASE_SECONDS = 30;
    private static final long TERMINAL_OBSERVATION_SECONDS = 900;
    private static final long LOGIN_RECOVERY_WINDOW_SECONDS = 900;

    private final ObjectProvider<HazelcastInstance> hazelcastProvider;
    private final AuthStateProperties properties;
    private final AuthUserSessionIndexStore userSessionIndexStore;
    private final HazelcastAuthStateAccess stateAccess;
    private final AuthQueuePositionTracker queuePositionTracker;
    private final HazelcastAuthSessionStore sessionStore;
    private final LoginQueueAdmissionCoordinator admissionCoordinator;
    private final HazelcastLoginQueueTicketTransitions loginQueueStateStore;
    private final AuthQueueRecoveryCoordinator recoveryCoordinator;
    private final FacadeHooks facadeHooks;
    private AuthQueueDiagnostics diagnostics;
    private boolean reservePermitOnReady;
    private final AtomicInteger operationRecordCount = new AtomicInteger();
    private final AtomicLong ticketOrderSequence = new AtomicLong();
    private final ConcurrentSkipListSet<Long> inFlightCreateOrders = new ConcurrentSkipListSet<>();
    private final ConcurrentSkipListMap<Long, String> pendingCreateOperations = new ConcurrentSkipListMap<>();
    private final AtomicBoolean createAppenderRunning = new AtomicBoolean();
    private final Object createLifecycleLock = new Object();
    private final Map<String, java.util.Iterator<?>> recoveryIterators = new ConcurrentHashMap<>();
    private final AtomicLong createRecoveryEntriesScanned = new AtomicLong();
    private final AtomicLong createRecoveryTicketsRecovered = new AtomicLong();
    private final AtomicLong createRecoveryTicketsRetried = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong createRecoveryCycleOldestPendingAgeSeconds = new AtomicLong();
    private long createRecoveryCycleTargetEntries;
    private long createRecoveryCycleInspectedEntries;
    private volatile boolean createRecoveryCycleComplete;

    public HazelcastLoginQueueStateStore(ObjectProvider<HazelcastInstance> hazelcastProvider,
            AuthStateProperties properties, FacadeHooks facadeHooks) {
        this.hazelcastProvider = hazelcastProvider;
        this.facadeHooks = facadeHooks;
        this.properties = properties;
        this.userSessionIndexStore = new AuthUserSessionIndexStore(hazelcastProvider, properties);
        this.stateAccess = new HazelcastAuthStateAccess(hazelcastProvider, properties);
        this.queuePositionTracker = new AuthQueuePositionTracker(stateAccess);
        this.sessionStore = new HazelcastAuthSessionStore(hazelcastProvider, properties, userSessionIndexStore,
                stateAccess, facadeHooks);
        this.admissionCoordinator = new LoginQueueAdmissionCoordinator(properties);
        this.recoveryCoordinator = new AuthQueueRecoveryCoordinator(stateAccess, sessionStore,
                userSessionIndexStore, admissionCoordinator, facadeHooks::indexLoginSession);
        this.loginQueueStateStore = new HazelcastLoginQueueTicketTransitions(properties, admissionCoordinator,
                new HazelcastLoginQueueTicketTransitions.Operations() {
                    @Override
                    public HazelcastInstance requireHazelcast() {
                        return HazelcastLoginQueueStateStore.this.requireHazelcast();
                    }

                    @Override
                    public void resolveAppendFence(HazelcastInstance hz, IMap<String, Ticket> tickets,
                            IQueue<QueueToken> order) {
                        HazelcastLoginQueueStateStore.this.resolveAppendFence(hz, tickets, order);
                    }

                    @Override
                    public boolean isTicketExpired(Ticket ticket, Instant now) {
                        return HazelcastLoginQueueStateStore.this.isTicketExpired(ticket, now);
                    }

                    @Override
                    public void expireTicket(IMap<String, Ticket> tickets, Ticket ticket) {
                        HazelcastLoginQueueStateStore.this.expireTicket(tickets, ticket);
                    }

                    @Override
                    public Optional<String> reserveBcryptPermit(String ticketId) {
                        return HazelcastLoginQueueStateStore.this.reserveBcryptPermit(ticketId);
                    }

                    @Override
                    public void releaseBcryptPermit(String owner, String key) {
                        HazelcastLoginQueueStateStore.this.releaseBcryptPermit(owner, key);
                    }

                    @Override
                    public void diagnosticEvent(String event, String ticketId, String status) {
                        HazelcastLoginQueueStateStore.this.diagnosticEvent(event, ticketId, status);
                    }

                    @Override
                    public String hmac(String value) {
                        return HazelcastLoginQueueStateStore.this.hmac(value);
                    }

                    @Override
                    public String resolveBoundTicketId(IMap<String, String> bindings, String cookieHash) {
                        return HazelcastLoginQueueStateStore.this.resolveBoundTicketId(bindings, cookieHash);
                    }

                    @Override
                    public void diagnosticLoginStage(String stage, String ticketId, String outcome, String errorCode,
                            long durationMillis, Throwable failure) {
                        HazelcastLoginQueueStateStore.this.diagnosticLoginStage(stage, ticketId, outcome, errorCode,
                                durationMillis, failure);
                    }

                    @Override
                    public boolean isClaimLeaseExpired(Ticket ticket, Instant now) {
                        return HazelcastLoginQueueStateStore.this.isClaimLeaseExpired(ticket, now);
                    }

                    @Override
                    public boolean ownsBcryptPermit(String owner, String key) {
                        return HazelcastLoginQueueStateStore.this.ownsBcryptPermit(owner, key);
                    }

                    @Override
                    public boolean recoverExpiredClaim(IMap<String, Ticket> tickets, Ticket ticket,
                            IMap<String, AuthSession> sessions) {
                        return recoveryCoordinator.recoverExpiredClaim(tickets, ticket, sessions);
                    }

                    @Override
                    public void enforceStatusReadLimit(String ticketId) {
                        HazelcastLoginQueueStateStore.this.enforceStatusReadLimit(ticketId);
                    }

                    @Override
                    public TicketView ticketView(Ticket ticket) {
                        return HazelcastLoginQueueStateStore.this.ticketView(ticket);
                    }

                    @Override
                    public void releaseActiveTicket(String ticketId) {
                        HazelcastLoginQueueStateStore.this.releaseActiveTicket(ticketId);
                    }

                    @Override
                    public boolean ownsClaim(Ticket current, Ticket claim) {
                        return HazelcastLoginQueueStateStore.this.ownsClaim(current, claim);
                    }

                    @Override
                    public long elapsedMillis(long startedNanos) {
                        return HazelcastLoginQueueStateStore.this.elapsedMillis(startedNanos);
                    }

                    @Override
                    public RuntimeException unavailable(RuntimeException exception) {
                        return HazelcastLoginQueueStateStore.this.unavailable(exception);
                    }

                    @Override
                    public void validateUuid(String value) {
                        HazelcastLoginQueueStateStore.this.validateUuid(value);
                    }

                    @Override
                    public String diagnosticCorrelationId(String ticketId) {
                        return HazelcastLoginQueueStateStore.this.diagnosticCorrelationId(ticketId);
                    }

                    @Override
                    public void diagnosticLoginRequestStage(String stage, String correlationId, String outcome,
                            String errorCode, long durationMillis, Throwable failure) {
                        HazelcastLoginQueueStateStore.this.diagnosticLoginRequestStage(stage, correlationId, outcome,
                                errorCode, durationMillis, failure);
                    }

                    @Override
                    public void requeueReadyTicket(IMap<String, Ticket> tickets, Ticket ticket) {
                        HazelcastLoginQueueStateStore.this.requeueReadyTicket(tickets, ticket);
                    }

                    @Override
                    public String localBcryptPermitKey(String ticketId) {
                        return admissionCoordinator.localBcryptPermitKey(ticketId);
                    }

                    @Override
                    public long bcryptPermitLeaseSeconds() {
                        return properties.bcryptPermitLeaseSeconds();
                    }

                    @Override
                    public void resumeRequeue(IMap<String, Ticket> tickets, Ticket ticket) {
                        HazelcastLoginQueueStateStore.this.resumeRequeue(tickets, ticket);
                    }

                    @Override
                    public boolean isLoginRecoveryValid(AuthSession session, Instant now) {
                        return sessionStore.isLoginRecoveryValid(session, now);
                    }
                }, () -> reservePermitOnReady);
    }

    public void setDiagnostics(AuthQueueDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    public void setReservePermitOnReady(boolean reservePermitOnReady) {
        this.reservePermitOnReady = reservePermitOnReady;
    }

    public String diagnosticCorrelationId(String ticketId) {
        if (diagnostics == null || !diagnostics.enabled() || ticketId == null || ticketId.isBlank()) {
            return null;
        }
        IMap<String, String> correlations = requireHazelcast().getMap(TICKET_DIAGNOSTICS);
        String existing = correlations.get(ticketId);
        if (existing != null) {
            return existing;
        }
        String created = diagnostics.correlationId(ticketId);
        String raced = correlations.putIfAbsent(ticketId, created,
                properties.queueTicketSeconds() + TERMINAL_OBSERVATION_SECONDS, TimeUnit.SECONDS);
        return raced == null ? created : raced;
    }

    public void diagnosticEvent(String event, String ticketId, String status) {
        if (diagnostics != null) {
            diagnostics.event(event, ticketId, status);
        }
    }

    public void diagnosticLoginStage(String stage, String ticketId, String outcome, String errorCode,
            long durationMillis, Throwable failure) {
        if (diagnostics != null) {
            diagnostics.stageForTicket(stage, ticketId, outcome, errorCode, durationMillis, failure);
        }
    }

    public void diagnosticLoginRequestStage(String stage, String correlationId, String outcome,
            String errorCode, long durationMillis, Throwable failure) {
        if (diagnostics != null) {
            diagnostics.stageForCorrelation(stage, correlationId, outcome, errorCode, durationMillis, failure);
        }
    }

    public AuthStatePermit acquireRequestPermit() {
        Semaphore semaphore = admissionCoordinator.acquireRequestPermit();
        try {
            return new AuthStatePermit(semaphore);
        } catch (RuntimeException exception) {
            semaphore.release();
            throw exception;
        }
    }

    public void enforceTicketCreateRateLimit(String clientAddress) {
        requireHazelcast();
        admissionCoordinator.enforceTicketCreateRateLimit(clientAddress, this::hmac);
    }

    public CreateTicket createOrReuseTicket(String suppliedCookie, String idempotencyKey) {
        long order = ticketOrderSequence.incrementAndGet();
        inFlightCreateOrders.add(order);
        try {
            return createOrReuseTicket(suppliedCookie, idempotencyKey, order);
        } finally {
            inFlightCreateOrders.remove(order);
        }
    }

    private CreateTicket createOrReuseTicket(String suppliedCookie, String idempotencyKey, long order) {
        synchronized (createLifecycleLock) {
            return createOrReuseTicketLocked(suppliedCookie, idempotencyKey, order);
        }
    }

    private CreateTicket createOrReuseTicketLocked(String suppliedCookie, String idempotencyKey, long order) {
        validateUuid(idempotencyKey);
        HazelcastInstance hz = requireHazelcast();
        String ticketId = hmac("ticket:" + idempotencyKey);
        IMap<String, Ticket> ticketMap = hz.getMap(TICKETS);
        ticketMap.lock(ticketId);
        try {
            if (Boolean.TRUE.equals(hz.<String, Boolean>getMap(CANCEL_FENCES).get(ticketId))) {
                throw new AuthStateConflictException("QUEUE_TICKET_CANCELLED");
            }
            return createOrReuseTicketUnderLifecycleLock(suppliedCookie, idempotencyKey, order);
        } finally {
            ticketMap.unlock(ticketId);
        }
    }

    private CreateTicket createOrReuseTicketUnderLifecycleLock(String suppliedCookie, String idempotencyKey,
            long order) {
        validateUuid(idempotencyKey);
        if (!admissionCoordinator.reservationsRestored()) {
            throw new AuthStateUnavailableException();
        }
        HazelcastInstance hz = requireHazelcast();
        IMap<String, Ticket> tickets = hz.getMap(TICKETS);
        IMap<String, String> bindings = hz.getMap(BINDINGS);
        IMap<String, CreateOperation> createOps = hz.getMap(CREATE_OPS);
        String keyHash = hmac("ticket:" + idempotencyKey);
        String cookie = cookieForOperation(idempotencyKey);
        String cookieHash = hmac("cookie:" + cookie);
        String suppliedCookieHash = suppliedCookie == null || suppliedCookie.isBlank()
                ? null
                : hmac("cookie:" + suppliedCookie);
        Instant now = Instant.now();

        CreateOperation previous = createOps.get(keyHash);
        if (previous != null) {
            if (previous.status() == CreateOperationStatus.CLAIMED
                    || previous.status() == CreateOperationStatus.OFFERING
                    || previous.status() == CreateOperationStatus.PENDING) {
                if (previous.ticketId() == null) {
                    throw new AuthStateOperationInProgressException(cookie);
                }
                if (!putOrRefreshBinding(bindings, cookieHash, previous.ticketId())) {
                    throw new AuthStateConflictException("IDEMPOTENCY_KEY_REUSED");
                }
                Ticket inProgress = tickets.get(previous.ticketId());
                TicketView view = inProgress == null
                        ? new TicketView(TicketStatus.WAITING, null, null, 6, previous.ticketId())
                        : ticketView(inProgress);
                return new CreateTicket(cookie, view, false);
            }
            CreateOperation touched = previous.withLastRequestedAt(now);
            if (!createOps.replace(keyHash, previous, touched)
                    || !createOps.setTtl(keyHash, operationRetentionSeconds(), TimeUnit.SECONDS)) {
                throw new AuthStateUnavailableException();
            }
            if (previous.ticketId() != null) {
                refreshBindingTtl(bindings, cookieHash, previous.ticketId());
                if (previous.cookieHash() != null) {
                    refreshBindingTtl(bindings, previous.cookieHash(), previous.ticketId());
                }
            }
            if (suppliedCookieHash != null && !suppliedCookieHash.equals(previous.cookieHash())) {
                String priorTicketId = resolveBoundTicketId(bindings, suppliedCookieHash);
                Ticket priorTicket = priorTicketId == null ? null : tickets.get(priorTicketId);
                if (priorTicket == null || isReusable(priorTicket)
                        && !priorTicket.ticketId().equals(previous.ticketId())) {
                    throw new AuthStateConflictException("IDEMPOTENCY_KEY_REUSED");
                }
            }
            Ticket existing = tickets.get(previous.ticketId());
            if (existing != null && !isTerminal(existing.status())) {
                return new CreateTicket(cookie, ticketView(existing), false);
            }
            if (previous.status() == CreateOperationStatus.ACCEPTED) {
                throw new AuthStateUnavailableException();
            }
            throw new AuthStateOperationInProgressException(cookie);
        }

        String boundTicketId = suppliedCookieHash == null
                ? null
                : resolveBoundTicketId(bindings, suppliedCookieHash);
        Ticket bound = boundTicketId == null ? null : tickets.get(boundTicketId);
        if (bound != null && isReusable(bound)) {
            CreateOperation reused = CreateOperation.accepted(bound.ticketId(), cookieHash, now);
            reserveOperationRecord();
            CreateOperation raced = createOps.putIfAbsent(keyHash, reused);
            if (raced != null) {
                releaseOperationRecord();
                throw new AuthStateOperationInProgressException(cookie);
            }
            setOperationRetention(createOps, keyHash);
            if (!putOrRefreshBinding(bindings, cookieHash, bound.ticketId())) {
                if (createOps.remove(keyHash, reused)) {
                    releaseOperationRecord();
                }
                throw new AuthStateConflictException("IDEMPOTENCY_KEY_REUSED");
            }
            return new CreateTicket(cookie, ticketView(bound), false);
        }

        String ticketId = keyHash;
        String ownerToken = UUID.randomUUID().toString();
        CreateOperation claimed = CreateOperation.claimed(ticketId, cookieHash, ownerToken, now,
                now.plusSeconds(OPERATION_CLAIM_LEASE_SECONDS));
        reserveOperationRecord();
        if (createOps.putIfAbsent(keyHash, claimed) != null) {
            releaseOperationRecord();
            throw new AuthStateOperationInProgressException(cookie);
        }
        setOperationRetention(createOps, keyHash);
        if (!putOrRefreshBinding(bindings, cookieHash, ticketId)) {
            if (createOps.remove(keyHash, claimed)) {
                releaseOperationRecord();
            }
            throw new AuthStateConflictException("IDEMPOTENCY_KEY_REUSED");
        }
        String reservationId = UUID.randomUUID().toString();
        if (!reserveActiveTicket(ticketId, reservationId)) {
            if (createOps.remove(keyHash, claimed)) {
                releaseOperationRecord();
            }
            bindings.remove(cookieHash, ticketId);
            throw new AuthStateTooManyRequestsException("QUEUE_FULL");
        }

        long generation = 1;
        CreateOperation pending = claimed.withReservation(ticketId, reservationId, now);
        if (!createOps.replace(keyHash, claimed, pending)) {
            releaseActiveTicket(ticketId, reservationId);
            throw new AuthStateUnavailableException();
        }
        Ticket creating = new Ticket(ticketId, order, now,
                now.plusSeconds(properties.queueTicketSeconds()), null, TicketStatus.CREATING,
                null, keyHash, null, null, null, null, null, generation);
        if (tickets.putIfAbsent(ticketId, creating) != null) {
            releaseActiveTicket(ticketId, reservationId);
            throw new AuthStateUnavailableException();
        }
        diagnosticEvent("TICKET_CREATED", ticketId, TicketStatus.CREATING.name());
        pendingCreateOperations.put(order, keyHash);
        return new CreateTicket(cookie, ticketView(creating), true);
    }

    public int processPendingCreateBatch(int maxItems) {
        synchronized (createLifecycleLock) {
            return processPendingCreateBatchLocked(maxItems);
        }
    }

    private int processPendingCreateBatchLocked(int maxItems) {
        if (maxItems < 1 || !createAppenderRunning.compareAndSet(false, true)) {
            return 0;
        }
        try {
            HazelcastInstance hz = requireHazelcast();
            IMap<String, CreateOperation> operations = hz.getMap(CREATE_OPS);
            IMap<String, Ticket> tickets = hz.getMap(TICKETS);
            IQueue<QueueToken> order = hz.getQueue(ORDER);
            Long oldestInFlight = inFlightCreateOrders.isEmpty() ? null : inFlightCreateOrders.first();
            int processed = 0;
            while (processed < maxItems && !pendingCreateOperations.isEmpty()) {
                Map.Entry<Long, String> pending = pendingCreateOperations.firstEntry();
                if (oldestInFlight != null && pending.getKey() > oldestInFlight) {
                    break;
                }
                CreateOperation operation = operations.get(pending.getValue());
                if (operation == null || operation.ticketId() == null
                        || operation.status() == CreateOperationStatus.TERMINAL) {
                    pendingCreateOperations.remove(pending.getKey(), pending.getValue());
                    continue;
                }
                Ticket ticket = tickets.get(operation.ticketId());
                if (ticket == null || isTerminal(ticket.status())) {
                    pendingCreateOperations.remove(pending.getKey(), pending.getValue());
                    continue;
                }
                if (operation.status() == CreateOperationStatus.PENDING && ticket.status() == TicketStatus.CREATING) {
                    recoverPendingCreate(pending.getValue(), operation, tickets, operations, order);
                }
                processed++;
                CreateOperation latestOperation = operations.get(pending.getValue());
                Ticket latestTicket = tickets.get(operation.ticketId());
                if ((latestOperation != null && latestOperation.status() == CreateOperationStatus.ACCEPTED)
                        || (latestTicket != null && isCreateProgressed(latestTicket.status()))) {
                    pendingCreateOperations.remove(pending.getKey(), pending.getValue());
                } else {
                    break;
                }
            }
            return processed;
        } finally {
            createAppenderRunning.set(false);
        }
    }

    public TicketView currentTicket(String queueCookie) {
        return loginQueueStateStore.currentTicket(queueCookie);
    }

    public void cancelCurrentTicket(String queueCookie) {
        loginQueueStateStore.cancelCurrentTicket(queueCookie);
    }

    public void cancelTicketById(String ticketId) {
        loginQueueStateStore.cancelTicketById(ticketId);
    }

    public Ticket claimReadyTicket(String queueCookie, String loginOperationId) {
        return loginQueueStateStore.claimReadyTicket(queueCookie, loginOperationId);
    }

    public Ticket claimReadyTicket(String queueCookie, String loginOperationId, String clientCorrelationId) {
        return loginQueueStateStore.claimReadyTicket(queueCookie, loginOperationId, clientCorrelationId);
    }

    public Ticket claimReadyTicket(String queueCookie, String loginOperationId, String clientCorrelationId,
            String requestedIdentityHash) {
        return loginQueueStateStore.claimReadyTicket(queueCookie, loginOperationId, clientCorrelationId,
                requestedIdentityHash);
    }

    public void completeTicket(Ticket ticket, LoginOutcome outcome, String sid) {
        loginQueueStateStore.completeTicket(ticket, outcome, sid);
    }

    public String loginIdentityHash(String email) {
        return loginQueueStateStore.loginIdentityHash(email);
    }

    public Ticket bindAuthenticationIdentity(Ticket claim, String identityHash) {
        return loginQueueStateStore.bindAuthenticationIdentity(claim, identityHash);
    }

    public Ticket bindAuthenticatedUser(Ticket claim, Long userId) {
        return loginQueueStateStore.bindAuthenticatedUser(claim, userId);
    }

    public Ticket markInvalidCredentials(Ticket claim) {
        return loginQueueStateStore.markInvalidCredentials(claim);
    }

    private Ticket updateClaimAuthentication(Ticket claim, java.util.function.Function<Ticket, Ticket> update,
            String phase, String identityHash, Long userId) {
        return loginQueueStateStore.updateClaimAuthentication(claim, update, phase, identityHash, userId);
    }

    public void reconcileFailedLogin(Ticket claim) {
        loginQueueStateStore.reconcileFailedLogin(claim);
    }

    public void reconcileFailedLogin(Ticket claim, LoginOutcome observedOutcome) {
        loginQueueStateStore.reconcileFailedLogin(claim, observedOutcome);
    }

    private boolean ownsClaim(Ticket current, Ticket claim) {
        return current != null && current.status() == TicketStatus.VERIFYING
                && java.util.Objects.equals(current.loginOperationId(), claim.loginOperationId())
                && java.util.Objects.equals(current.claimId(), claim.claimId());
    }

    public AuthSession loginRecoverySession(String queueCookie, String operationId) {
        HazelcastInstance hz = requireHazelcast();
        String ticketId = resolveBoundTicketId(hz.<String, String>getMap(BINDINGS), hmac("cookie:" + queueCookie));
        Ticket ticket = ticketId == null ? null : hz.<String, Ticket>getMap(TICKETS).get(ticketId);
        if (ticketId != null) {
            enforceRateLimit(ticketId, "recovery");
        }
        if (ticket == null || ticket.status() != TicketStatus.CONSUMED
                || ticket.loginOutcome() != LoginOutcome.SUCCESS
                || !operationId.equals(ticket.loginOperationId()) || ticket.sid() == null) {
            throw new AuthStateConflictException("LOGIN_RECOVERY_UNAVAILABLE");
        }
        AuthSession session = hz.<String, AuthSession>getMap(SESSIONS).get(ticket.sid());
        if (session == null || session.status() != SessionStatus.ACTIVE
                || !operationId.equals(session.loginOperationId())
                || session.loginRecoveryUntil() == null || !session.loginRecoveryUntil().isAfter(Instant.now())
                || session.generation() != session.loginRecoveryGeneration()
                || !session.currentRefreshHash().equals(session.loginRecoveryRefreshHash())
                || session.encryptedLoginRecoveryRefreshToken() == null) {
            throw new AuthStateConflictException("LOGIN_RECOVERY_UNAVAILABLE");
        }
        return session;
    }

    public void reconcileVerifyingTickets() {
        reconcileVerifyingTickets(new ReconciliationAccumulator());
    }

    public void reconcileVerifyingTickets(ReconciliationAccumulator observations) {
        recoveryCoordinator.reconcileScheduledState(() -> reconcileQueueTicketState(observations));
    }

    private AuthQueueRecoveryCoordinator.ScheduledRecoveryState reconcileQueueTicketState(
            ReconciliationAccumulator observations) {
        HazelcastInstance hz = requireHazelcast();
        IMap<String, Ticket> tickets = hz.getMap(TICKETS);
        IMap<String, AuthSession> sessions = hz.getMap(SESSIONS);
        IMap<String, String> loginSessionIndex = hz.getMap(LOGIN_SESSION_INDEX);
        IMap<String, CreateOperation> createOps = hz.getMap(CREATE_OPS);
        IQueue<QueueToken> order = hz.getQueue(ORDER);
        List<Ticket> expiredClaims = new java.util.ArrayList<>();
        List<Map.Entry<String, CreateOperation>> createOperationBatch;
        try {
            createOperationBatch = boundedCreateOperations(createOps);
            Instant recoveryNow = Instant.now();
            long oldestPendingAge = oldestPendingAge(createOperationBatch, tickets, recoveryNow);
            for (Map.Entry<String, CreateOperation> entry : createOperationBatch) {
                CreateOperation operation = entry.getValue();
                if (operation.status() == CreateOperationStatus.CLAIMED
                        && operation.leaseUntil() != null && !operation.leaseUntil().isAfter(Instant.now())) {
                    if (operation.ticketId() == null) {
                        if (createOps.remove(entry.getKey(), operation)) {
                            releaseOperationRecord();
                        }
                        continue;
                    }
                    String ticketId = operation.ticketId();
                    synchronized (createLifecycleLock) {
                        CreateOperation current = createOps.get(entry.getKey());
                        if (!operation.equals(current) || current.leaseUntil() == null
                                || current.leaseUntil().isAfter(Instant.now())) {
                            continue;
                        }
                        Instant now = Instant.now();
                        String ownerToken = UUID.randomUUID().toString();
                        String reservationId = UUID.randomUUID().toString();
                        CreateOperation takeover = current.withTakeover(ownerToken, reservationId,
                                now.plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), now);
                        if (!createOps.replace(entry.getKey(), current, takeover)) {
                            continue;
                        }
                        if (!transferActiveTicketReservation(ticketId, current.reservationId(), reservationId)) {
                            continue;
                        }
                        if (!putOrRefreshBinding(hz.<String, String>getMap(BINDINGS),
                                current.cookieHash(), ticketId)) {
                            throw new AuthStateUnavailableException();
                        }
                        CreateOperation pending = takeover.withReservation(ticketId, reservationId, now);
                        if (createOps.replace(entry.getKey(), takeover, pending)) {
                            Instant createdAt = current.lastRequestedAt() == null ? now : current.lastRequestedAt();
                            Ticket recovered = new Ticket(ticketId, 0, createdAt,
                                    createdAt.plusSeconds(properties.queueTicketSeconds()), null, TicketStatus.CREATING,
                                    null, entry.getKey(), null, null, null, null, null, 1);
                            tickets.putIfAbsent(ticketId, recovered);
                            recoverPendingCreate(entry.getKey(), pending, tickets, createOps, order);
                        }
                    }
                } else if (operation.status() == CreateOperationStatus.OFFERING
                        && operation.ticketId() != null) {
                    recoverOfferingCreate(entry.getKey(), operation, tickets, createOps, order);
                } else if (operation.status() == CreateOperationStatus.PENDING
                        && operation.ticketId() != null) {
                    Ticket pendingTicket = tickets.get(operation.ticketId());
                    if (pendingTicket == null) {
                        recoverPendingCreate(entry.getKey(), operation, tickets, createOps, order);
                    } else if (pendingTicket.status() == TicketStatus.CREATING) {
                        long pendingOrder = pendingTicket.order() > 0
                                ? pendingTicket.order()
                                : pendingTicket.createdAt().toEpochMilli();
                        pendingCreateOperations.putIfAbsent(pendingOrder, entry.getKey());
                    }
                } else if (operation.status() == CreateOperationStatus.ACCEPTED
                        && operation.ticketId() != null) {
                    Ticket ticket = tickets.get(operation.ticketId());
                    if (ticket != null && ticket.status() == TicketStatus.CREATING) {
                        recoverPendingCreate(entry.getKey(), operation, tickets, createOps, order);
                    } else if (ticket == null || isTerminal(ticket.status())) {
                        createOps.replace(entry.getKey(), operation,
                                operation.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
                    }
                }
            }
            createRecoveryEntriesScanned.addAndGet(createOperationBatch.size());
            createRecoveryCycleOldestPendingAgeSeconds.accumulateAndGet(oldestPendingAge, Math::max);
            if (createRecoveryCycleComplete) {
                oldestPendingAgeSeconds.set(createRecoveryCycleOldestPendingAgeSeconds.getAndSet(0));
                createRecoveryCycleComplete = false;
            }
        } catch (RuntimeException exception) {
            resetCreateRecoveryCycle(createOps);
            throw exception;
        }
        List<Ticket> ticketBatch = boundedValues(tickets, RECOVERY_BATCH_SIZE, "ticket-reconciliation");
        for (Ticket ticket : ticketBatch) {
            if (ticket.status() == TicketStatus.CREATING && isTicketExpired(ticket, Instant.now())) {
                QueueToken token = new QueueToken(ticket.ticketId(), ticket.enqueueGeneration());
                expireTicket(tickets, ticket);
                clearAppendFence(token);
                IMap<String, CreateOperation> operations = hz.getMap(CREATE_OPS);
                CreateOperation operation = ticket.createOperationId() == null
                        ? null
                        : operations.get(ticket.createOperationId());
                if (operation != null && operation.status() != CreateOperationStatus.TERMINAL) {
                    operations.replace(ticket.createOperationId(), operation,
                            operation.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
                }
                continue;
            }
            if (ticket.status() == TicketStatus.VERIFYING && isClaimLeaseExpired(ticket, Instant.now())) {
                expiredClaims.add(ticket);
                observations.expiredClaimScanned();
                continue;
            }
            if (ticket.status() == TicketStatus.RETRYABLE) {
                observations.retryableScanned();
                if (isTicketExpired(ticket, Instant.now())) {
                    expireTicket(tickets, ticket);
                    IMap<String, CreateOperation> operations = hz.getMap(CREATE_OPS);
                    CreateOperation operation = operations.get(ticket.createOperationId());
                    if (operation != null && operation.status() != CreateOperationStatus.TERMINAL) {
                        operations.replace(ticket.createOperationId(), operation,
                                operation.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
                    }
                } else {
                    Ticket requeue = ticket.withEnqueue(0, ticket.enqueueGeneration() + 1,
                            TicketStatus.REQUEUING, null);
                    boolean replaced;
                    try {
                        replaced = replaceTicketState(tickets, ticket, requeue);
                    } catch (RuntimeException exception) {
                        observations.retryableRequeue(ReconciliationOutcome.ERROR);
                        observations.diagnostic(ReconciliationStage.RETRYABLE_REQUEUE, exception);
                        throw exception;
                    }
                    observations.retryableRequeue(replaced
                            ? ReconciliationOutcome.SUCCESS
                            : ReconciliationOutcome.MISS);
                    if (replaced) {
                        resumeRequeue(tickets, requeue, observations);
                    }
                }
                continue;
            }
            if (ticket.status() == TicketStatus.REQUEUING) {
                resumeRequeue(tickets, ticket, observations);
                continue;
            }
            if (ticket.status() == TicketStatus.REQUEUE_OFFERING
                    && ticket.claimLeaseUntil() != null && !ticket.claimLeaseUntil().isAfter(Instant.now())) {
                Ticket unknown = ticket.withState(TicketStatus.REQUEUING_UNKNOWN,
                        null, null, null, Instant.now().plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), null, null, null);
                replaceForReconciliation(tickets, ticket, unknown, ReconciliationStage.OFFERING_UNKNOWN,
                        observations);
                continue;
            }
            if (ticket.status() == TicketStatus.REQUEUING_UNKNOWN) {
                QueueToken token = new QueueToken(ticket.ticketId(), ticket.enqueueGeneration());
                if (order.contains(token)) {
                    Ticket waiting = ticket.withState(TicketStatus.WAITING, null, null, null, null,
                            null, null, null);
                    if (replaceForReconciliation(tickets, ticket, waiting, ReconciliationStage.UNKNOWN_WAITING,
                            observations)) {
                        clearAppendFenceAfterRequeue(token, observations);
                    }
                } else if (ticket.claimLeaseUntil() == null || !ticket.claimLeaseUntil().isAfter(Instant.now())) {
                    expireTicket(tickets, ticket);
                    clearAppendFence(token);
                }
                continue;
            }
            if (ticket.status() == TicketStatus.ADMITTING) {
                String permitKey = admissionCoordinator.localBcryptPermitKey(ticket.ticketId());
                if (permitKey == null) {
                    Ticket waiting = ticket.withState(TicketStatus.WAITING, null, null, null, null,
                            null, null, null);
                    tickets.replace(ticket.ticketId(), ticket, waiting);
                    continue;
                }
                if (isTicketExpired(ticket, Instant.now())) {
                    if (isTicketExpired(ticket, Instant.now())) {
                        expireTicket(tickets, ticket);
                    }
                    continue;
                }
                Instant readyExpiry = min(ticket.expiresAt(), Instant.now()
                        .plusSeconds(properties.readyTicketSeconds()));
                Ticket ready = ticket.withState(TicketStatus.READY, null, null, null, null,
                        readyExpiry, permitKey, null);
                if (tickets.replace(ticket.ticketId(), ticket, ready)) {
                    order.remove(new QueueToken(ticket.ticketId(), ticket.enqueueGeneration()));
                }
                continue;
            }
        }
        IMap<String, UserSessionIndex> userIndexes = hz.getMap("auth-user-session-index-v2");
        return new AuthQueueRecoveryCoordinator.ScheduledRecoveryState(hz, loginSessionIndex, tickets, sessions,
                boundedEntries(userIndexes, RECOVERY_BATCH_SIZE),
                boundedValues(sessions, RECOVERY_BATCH_SIZE, "session-reconciliation"), expiredClaims, observations,
                this::ttlSeconds);
    }

    private void recoverPendingCreate(String operationId, CreateOperation operation,
            IMap<String, Ticket> tickets,
            IMap<String, CreateOperation> operations, IQueue<QueueToken> order) {
        synchronized (createLifecycleLock) {
            recoverPendingCreateLocked(operationId, operation, tickets, operations, order);
        }
    }

    private void recoverPendingCreateLocked(String operationId, CreateOperation operation,
            IMap<String, Ticket> tickets, IMap<String, CreateOperation> operations, IQueue<QueueToken> order) {
        if (operation.ticketId() == null) {
            return;
        }
        Ticket ticket = tickets.get(operation.ticketId());
        if (ticket == null) {
            Instant now = Instant.now();
            Instant createdAt = operation.lastRequestedAt() == null ? now : operation.lastRequestedAt();
            Ticket recovered = new Ticket(operation.ticketId(), 0, createdAt,
                    createdAt.plusSeconds(properties.queueTicketSeconds()), null, TicketStatus.CREATING,
                    null, operationId, null, null, null, null, null, 1);
            tickets.putIfAbsent(operation.ticketId(), recovered);
            ticket = tickets.get(operation.ticketId());
            if (ticket != null) {
                createRecoveryTicketsRecovered.incrementAndGet();
            }
        }
        if (ticket == null || !operation.ticketId().equals(ticket.ticketId())) {
            return;
        }
        QueueToken token = new QueueToken(ticket.ticketId(), ticket.enqueueGeneration());
        if (isTicketExpired(ticket, Instant.now())) {
            expirePendingCreate(operation, ticket, tickets, operations, order, token);
            return;
        }
        String marker = operation.reservationId();
        if (marker != null && marker.startsWith("retry-same-generation:")) {
            resumeSameGenerationCreateRetry(operationId, operation, tickets, operations, order);
            return;
        }
        if (marker != null && marker.startsWith("retry-generation:")) {
            resumeCreateRetryMarker(operationId, operation, tickets, operations, order);
            return;
        }
        if (order.contains(token)) {
            finalizeCreateAccepted(operationId, operation, ticket, tickets, operations, token);
            return;
        }
        if (isCreateProgressed(ticket.status())) {
            finalizeCreateAccepted(operationId, operation, ticket, tickets, operations, token);
            return;
        }
        if (ticket.status() == TicketStatus.EXPIRED || ticket.status() == TicketStatus.CANCELLED) {
            operations.replace(operationId, operation,
                    operation.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
            return;
        }
        if (ticket.status() != TicketStatus.CREATING) {
            return;
        }
        beforePendingCreateCas(operationId);
        Instant now = Instant.now();
        CreateOperation offering = operation.status() == CreateOperationStatus.ACCEPTED
                ? operation.withSameGenerationRetryMarker(ticket.enqueueGeneration(), now)
                : operation.withOffering(now);
        if (operations.replace(operationId, operation, offering)) {
            beforeCreateQueueOffer(operationId);
            attemptCreateOffer(operationId, offering, ticket, tickets, operations, order);
        }
    }

    void beforePendingCreateCas(String operationId) {
        facadeHooks.beforePendingCreateCas(operationId);
    }

    void beforeCreateQueueOffer(String operationId) {
        facadeHooks.beforeCreateQueueOffer(operationId);
    }

    private void recoverOfferingCreate(String operationId, CreateOperation operation, IMap<String, Ticket> tickets,
            IMap<String, CreateOperation> operations, IQueue<QueueToken> order) {
        String marker = operation.reservationId();
        Ticket ticket = operation.ticketId() == null ? null : tickets.get(operation.ticketId());
        if (ticket == null) {
            operations.replace(operationId, operation,
                    operation.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
            return;
        }
        QueueToken token = new QueueToken(ticket.ticketId(), ticket.enqueueGeneration());
        if (isTicketExpired(ticket, Instant.now())) {
            expirePendingCreate(operation, ticket, tickets, operations, order, token);
            return;
        }
        if (marker != null && marker.startsWith("retry-same-generation:")) {
            resumeSameGenerationCreateRetry(operationId, operation, tickets, operations, order);
            return;
        }
        if (marker != null && marker.startsWith("retry-generation:")) {
            resumeCreateRetryMarker(operationId, operation, tickets, operations, order);
            return;
        }
        if (operation.leaseUntil() != null && operation.leaseUntil().isAfter(Instant.now())) {
            return;
        }
        if (order.contains(token)) {
            finalizeCreateAccepted(operationId, operation, ticket, tickets, operations, token);
            return;
        }
        if (isCreateProgressed(ticket.status())) {
            finalizeCreateAccepted(operationId, operation, ticket, tickets, operations, token);
            return;
        }
        if (ticket.status() == TicketStatus.EXPIRED || ticket.status() == TicketStatus.CANCELLED) {
            operations.replace(operationId, operation,
                    operation.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
            clearAppendFence(token);
            return;
        }
        if (ticket.status() != TicketStatus.CREATING) {
            return;
        }
        HazelcastInstance hz = requireHazelcast();
        IMap<String, QueueOfferFence> fences = hz.getMap(APPEND_FENCES);
        QueueOfferFence fence = fences.get("active");
        if (fence != null && fence.deadline().isAfter(Instant.now())) {
            return;
        }
        if (fence != null) {
            fences.remove("active", fence);
        }
        long target = Math.max(ticket.enqueueGeneration() + 1, retryTarget(marker, ticket.enqueueGeneration()));
        CreateOperation marked = operation.withRetryMarker(target, Instant.now());
        if (operations.replace(operationId, operation, marked)) {
            resumeCreateRetryMarker(operationId, marked, tickets, operations, order);
        }
    }

    private void expirePendingCreate(CreateOperation operation, Ticket ticket, IMap<String, Ticket> tickets,
            IMap<String, CreateOperation> operations, IQueue<QueueToken> order, QueueToken token) {
        terminalizeCreate(tickets, operations, ticket, operation);
        order.remove(token);
        clearAppendFence(token);
    }

    private void resumeSameGenerationCreateRetry(String operationId, CreateOperation operation,
            IMap<String, Ticket> tickets, IMap<String, CreateOperation> operations, IQueue<QueueToken> order) {
        Ticket ticket = operation.ticketId() == null ? null : tickets.get(operation.ticketId());
        if (ticket == null) {
            return;
        }
        QueueToken token = new QueueToken(ticket.ticketId(), ticket.enqueueGeneration());
        if (order.contains(token)) {
            finalizeCreateAccepted(operationId, operation, ticket, tickets, operations, token);
            return;
        }
        if (isCreateProgressed(ticket.status())) {
            finalizeCreateAccepted(operationId, operation, ticket, tickets, operations, token);
            return;
        }
        if (ticket.status() == TicketStatus.EXPIRED || ticket.status() == TicketStatus.CANCELLED) {
            operations.replace(operationId, operation,
                    operation.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
            return;
        }
        if (ticket.status() != TicketStatus.CREATING) {
            return;
        }
        HazelcastInstance hz = requireHazelcast();
        IMap<String, QueueOfferFence> fences = hz.getMap(APPEND_FENCES);
        QueueOfferFence fence = fences.get("active");
        if (fence != null && fence.deadline().isAfter(Instant.now())) {
            return;
        }
        if (fence != null) {
            fences.remove("active", fence);
        }
        CreateOperation renewed = operation.withSameGenerationRetryMarker(ticket.enqueueGeneration(), Instant.now());
        if (operations.replace(operationId, operation, renewed)) {
            attemptCreateOffer(operationId, renewed, ticket, tickets, operations, order);
        }
    }

    private void attemptCreateOffer(String operationId, CreateOperation offering, Ticket ticket,
            IMap<String, Ticket> tickets, IMap<String, CreateOperation> operations, IQueue<QueueToken> order) {
        synchronized (createLifecycleLock) {
            attemptCreateOfferLocked(operationId, offering, ticket, tickets, operations, order);
        }
    }

    private void attemptCreateOfferLocked(String operationId, CreateOperation offering, Ticket ticket,
            IMap<String, Ticket> tickets, IMap<String, CreateOperation> operations, IQueue<QueueToken> order) {
        CreateOperation currentOwner = operations.get(operationId);
        Ticket currentTicket = tickets.get(ticket.ticketId());
        if (currentOwner == null || !currentOwner.equals(offering)
                || !java.util.Objects.equals(currentOwner.ownerToken(), offering.ownerToken())
                || currentTicket == null || currentTicket.status() != TicketStatus.CREATING
                || currentTicket.enqueueGeneration() != ticket.enqueueGeneration()) {
            return;
        }
        ticket = currentTicket;
        QueueToken token = new QueueToken(ticket.ticketId(), ticket.enqueueGeneration());
        if (order.contains(token)) {
            diagnosticEvent("FIFO_REGISTERED", ticket.ticketId(), ticket.status().name());
            finalizeCreateAccepted(operationId, offering, ticket, tickets, operations, token);
            return;
        }
        boolean offered;
        try {
            offered = offerQueueToken(token);
        } catch (RuntimeException exception) {
            if (exception instanceof AppendFencedException) {
                if (order.contains(token)) {
                    finalizeCreateAccepted(operationId, offering, ticket, tickets, operations, token);
                } else {
                    operations.replace(operationId, offering, offering.withRetryablePending(Instant.now()));
                    createRecoveryTicketsRetried.incrementAndGet();
                }
            }
            return;
        }
        if (!offered) {
            operations.replace(operationId, offering,
                    offering.withRetryablePending(Instant.now()));
            createRecoveryTicketsRetried.incrementAndGet();
            return;
        }
        diagnosticEvent("FIFO_REGISTERED", ticket.ticketId(), TicketStatus.WAITING.name());
        Ticket latest = tickets.get(ticket.ticketId());
        if (latest != null && latest.status() == TicketStatus.CREATING
                && latest.enqueueGeneration() == token.generation()) {
            Ticket waiting = latest.withState(TicketStatus.WAITING, null, null, null, null, null, null, null);
            tickets.replace(latest.ticketId(), latest, waiting);
        }
        latest = tickets.get(ticket.ticketId());
        if (latest != null && isCreateProgressed(latest.status())) {
            CreateOperation current = operations.get(operationId);
            if (current != null && current.status() == CreateOperationStatus.OFFERING) {
                operations.replace(operationId, current,
                        current.withStatus(CreateOperationStatus.ACCEPTED, Instant.now()));
            }
            clearAppendFence(token);
            createRecoveryTicketsRecovered.incrementAndGet();
        }
    }

    private void finalizeCreateAccepted(String operationId, CreateOperation operation, Ticket ticket,
            IMap<String, Ticket> tickets, IMap<String, CreateOperation> operations, QueueToken token) {
        if (ticket.status() == TicketStatus.CREATING) {
            Ticket waiting = ticket.withState(TicketStatus.WAITING, null, null, null, null, null, null, null);
            if (tickets.replace(ticket.ticketId(), ticket, waiting)) {
                ticket = waiting;
            } else {
                ticket = tickets.get(ticket.ticketId());
            }
        }
        if (ticket != null && isCreateProgressed(ticket.status())) {
            CreateOperation current = operations.get(operationId);
            if (current != null && (current.status() == CreateOperationStatus.PENDING
                    || current.status() == CreateOperationStatus.OFFERING)) {
                operations.replace(operationId, current,
                        current.withStatus(CreateOperationStatus.ACCEPTED, Instant.now()));
            }
            clearAppendFence(token);
        }
    }

    private void resumeCreateRetryMarker(String operationId, CreateOperation marked, IMap<String, Ticket> tickets,
            IMap<String, CreateOperation> operations, IQueue<QueueToken> order) {
        long target = retryTarget(marked.reservationId(), -1);
        if (target < 0 || marked.ticketId() == null) {
            return;
        }
        Ticket ticket = tickets.get(marked.ticketId());
        if (ticket == null) {
            return;
        }
        QueueToken oldToken = new QueueToken(ticket.ticketId(), ticket.enqueueGeneration());
        if (order.contains(oldToken)) {
            finalizeCreateAccepted(operationId, marked, ticket, tickets, operations, oldToken);
            return;
        }
        if (isCreateProgressed(ticket.status())) {
            CreateOperation current = operations.get(operationId);
            if (current != null && current.equals(marked)) {
                operations.replace(operationId, current,
                        current.withStatus(CreateOperationStatus.ACCEPTED, Instant.now()));
            }
            clearAppendFence(oldToken);
            return;
        }
        if (ticket.status() == TicketStatus.EXPIRED || ticket.status() == TicketStatus.CANCELLED) {
            operations.replace(operationId, marked,
                    marked.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
            clearAppendFence(oldToken);
            return;
        }
        if (ticket.status() != TicketStatus.CREATING) {
            return;
        }
        IMap<String, QueueOfferFence> fences = requireHazelcast().getMap(APPEND_FENCES);
        QueueOfferFence observedFence = fences.get("active");
        if (observedFence != null && observedFence.deadline().isAfter(Instant.now())) {
            return;
        }
        if (observedFence != null && !fences.remove("active", observedFence)) {
            QueueOfferFence replacementFence = fences.get("active");
            if (replacementFence != null && replacementFence.deadline().isAfter(Instant.now())) {
                return;
            }
            if (replacementFence != null && !fences.remove("active", replacementFence)) {
                return;
            }
        }
        if (ticket.enqueueGeneration() < target) {
            Ticket rotated = ticket.withGeneration(target, TicketStatus.CREATING, null);
            if (!replaceCreateGeneration(tickets, ticket, rotated)) {
                ticket = tickets.get(ticket.ticketId());
                if (ticket == null) {
                    return;
                }
                if (isCreateProgressed(ticket.status())) {
                    CreateOperation current = operations.get(operationId);
                    if (current != null && current.equals(marked)) {
                        operations.replace(operationId, current,
                                current.withStatus(CreateOperationStatus.ACCEPTED, Instant.now()));
                    }
                    clearAppendFence(new QueueToken(ticket.ticketId(), ticket.enqueueGeneration()));
                    return;
                }
                if (ticket.status() != TicketStatus.CREATING || ticket.enqueueGeneration() < target) {
                    return;
                }
            }
        }
        CreateOperation current = operations.get(operationId);
        if (current != null && current.equals(marked)) {
            if (operations.replace(operationId, current,
                    current.withStatus(CreateOperationStatus.PENDING, Instant.now()))) {
                createRecoveryTicketsRetried.incrementAndGet();
            }
        }
    }

    boolean replaceCreateGeneration(IMap<String, Ticket> tickets, Ticket expected, Ticket rotated) {
        return facadeHooks.replaceCreateGeneration(tickets, expected, rotated);
    }

    public boolean doReplaceCreateGeneration(IMap<String, Ticket> tickets, Ticket expected, Ticket rotated) {
        return tickets.replace(expected.ticketId(), expected, rotated);
    }

    private boolean isCreateProgressed(TicketStatus status) {
        return status == TicketStatus.WAITING || status == TicketStatus.ADMITTING || status == TicketStatus.READY
                || status == TicketStatus.VERIFYING || status == TicketStatus.RETRYABLE
                || status == TicketStatus.REQUEUING || status == TicketStatus.REQUEUE_OFFERING
                || status == TicketStatus.REQUEUING_UNKNOWN || status == TicketStatus.CONSUMED;
    }

    private long retryTarget(String marker, long fallback) {
        if (marker == null || !marker.startsWith("retry-generation:")) {
            return fallback;
        }
        try {
            return Long.parseLong(marker.substring("retry-generation:".length()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    synchronized List<Map.Entry<String, CreateOperation>> boundedCreateOperations(
            IMap<String, CreateOperation> map) {
        return facadeHooks.boundedCreateOperations(map);
    }

    public List<Map.Entry<String, CreateOperation>> doBoundedCreateOperations(
            IMap<String, CreateOperation> map) {
        String cursorKey = map.getName() + ":pending-create-recovery";
        java.util.Iterator<?> cursor = recoveryIterators.get(cursorKey);
        if (cursor == null) {
            createRecoveryCycleTargetEntries = Math.max(1, map.size());
            createRecoveryCycleInspectedEntries = 0;
            cursor = map.iterator(RECOVERY_BATCH_SIZE);
            recoveryIterators.put(cursorKey, cursor);
        }
        java.util.Iterator<Map.Entry<String, CreateOperation>> iterator = createOperationIterator(cursor);
        List<Map.Entry<String, CreateOperation>> result = new java.util.ArrayList<>(RECOVERY_BATCH_SIZE);
        int inspected = 0;
        while (iterator.hasNext() && inspected < RECOVERY_BATCH_SIZE
                && createRecoveryCycleInspectedEntries < createRecoveryCycleTargetEntries) {
            inspected++;
            createRecoveryCycleInspectedEntries++;
            Map.Entry<String, CreateOperation> entry = iterator.next();
            if (entry != null && entry.getValue() != null) {
                result.add(entry);
            }
        }
        if (createRecoveryCycleInspectedEntries >= createRecoveryCycleTargetEntries || !iterator.hasNext()) {
            recoveryIterators.remove(cursorKey);
            createRecoveryCycleComplete = true;
        }
        return result;
    }

    long oldestPendingAge(List<Map.Entry<String, CreateOperation>> operations,
            IMap<String, Ticket> tickets, Instant now) {
        long oldestAge = 0;
        for (Map.Entry<String, CreateOperation> entry : operations) {
            CreateOperation operation = entry.getValue();
            if (operation.status() != CreateOperationStatus.PENDING
                    && operation.status() != CreateOperationStatus.OFFERING) {
                continue;
            }
            Ticket ticket = operation.ticketId() == null ? null : tickets.get(operation.ticketId());
            Instant ageBase = ticket == null ? operation.lastRequestedAt() : ticket.createdAt();
            if (ageBase != null) {
                oldestAge = Math.max(oldestAge, Math.max(0, Duration.between(ageBase, now).toSeconds()));
            }
        }
        return oldestAge;
    }

    private synchronized void resetCreateRecoveryCycle(IMap<String, CreateOperation> map) {
        recoveryIterators.remove(map.getName() + ":pending-create-recovery");
        createRecoveryCycleTargetEntries = 0;
        createRecoveryCycleInspectedEntries = 0;
        createRecoveryCycleOldestPendingAgeSeconds.set(0);
        createRecoveryCycleComplete = false;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Iterator<Map.Entry<String, CreateOperation>> createOperationIterator(Object cursor) {
        return (java.util.Iterator<Map.Entry<String, CreateOperation>>) cursor;
    }

    public long createRecoveryEntriesScanned() {
        return createRecoveryEntriesScanned.get();
    }

    public long createRecoveryTicketsRecovered() {
        return createRecoveryTicketsRecovered.get();
    }

    public long createRecoveryTicketsRetried() {
        return createRecoveryTicketsRetried.get();
    }

    public long oldestPendingAgeSeconds() {
        return oldestPendingAgeSeconds.get();
    }

    public int activeTicketCount() {
        return admissionCoordinator.activeTicketCount();
    }

    public Optional<String> reserveBcryptPermit(String ticketId) {
        return admissionCoordinator.reserveBcryptPermit(ticketId);
    }

    public boolean renewBcryptPermit(String owner, String key) {
        return admissionCoordinator.renewBcryptPermit(owner, key);
    }

    public void releaseBcryptPermit(String owner, String key) {
        admissionCoordinator.releaseBcryptPermit(owner, key);
    }

    public boolean ownsBcryptPermit(String owner, String key) {
        return admissionCoordinator.ownsBcryptPermit(owner, key);
    }

    public void releaseUnclaimedBcryptPermit(String owner, String key) {
        if (owner == null || key == null) {
            return;
        }
        IMap<String, Ticket> tickets = requireHazelcast().getMap(TICKETS);
        Ticket ticket = tickets.get(owner);
        boolean claimed = ticket != null
                && (ticket.status() == TicketStatus.ADMITTING || ticket.status() == TicketStatus.READY
                        || ticket.status() == TicketStatus.VERIFYING)
                && key.equals(ticket.bcryptPermitKey());
        if (!claimed) {
            releaseBcryptPermit(owner, key);
        }
    }

    public void renewLocalBcryptPermits() {
        IMap<String, Ticket> tickets = requireHazelcast().getMap(TICKETS);
        Instant now = Instant.now();
        for (Ticket ticket : boundedValues(tickets, RECOVERY_BATCH_SIZE, "bcrypt-permit-renewal")) {
            if (ticket.status() == TicketStatus.READY) {
                if (isTicketExpired(ticket, now)) {
                    expireTicket(tickets, ticket);
                }
            } else if (ticket.status() == TicketStatus.VERIFYING
                    && ticket.bcryptPermitKey() != null
                    && ticket.bcryptPermitKey().equals(admissionCoordinator.localBcryptPermitKey(ticket.ticketId()))) {
                renewBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey());
            }
        }
    }

    public Optional<Ticket> nextWaitingTicket() {
        return loginQueueStateStore.nextWaitingTicket();
    }

    public boolean admit(Ticket ticket) {
        return loginQueueStateStore.admit(ticket);
    }

    public boolean admit(Ticket ticket, String ignoredPermitKey) {
        if (ignoredPermitKey != null) {
            releaseBcryptPermit(ticket.ticketId(), ignoredPermitKey);
        }
        return admit(ticket);
    }

    public Integer estimatedUsersAhead(Ticket ticket) {
        return queuePositionTracker.estimatedUsersAhead(ticket);
    }

    public void refreshQueuePositionSnapshot() {
        queuePositionTracker.refreshQueuePositionSnapshot();
    }

    private TicketView ticketView(Ticket ticket) {
        return queuePositionTracker.ticketView(ticket);
    }

    public void requeueReadyTicket(IMap<String, Ticket> tickets, Ticket ticket) {
        if (ticket == null || ticket.status() != TicketStatus.RETRYABLE) {
            return;
        }
        if (isTicketExpired(ticket, Instant.now())) {
            expireTicket(tickets, ticket);
            diagnosticLoginStage("IMMEDIATE_REQUEUE", ticket.ticketId(), "EXPIRED", "TICKET_EXPIRED", 0, null);
            return;
        }
        long nextGeneration = ticket.enqueueGeneration() + 1;
        Ticket requeue = ticket.withEnqueue(0, nextGeneration,
                TicketStatus.REQUEUING, null);
        if (!tickets.replace(ticket.ticketId(), ticket, requeue)) {
            diagnosticLoginStage("IMMEDIATE_REQUEUE", ticket.ticketId(), "CAS_MISS", "NONE", 0, null);
            return;
        }
        releaseBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey());
        long offerStarted = System.nanoTime();
        try {
            resumeRequeue(tickets, requeue);
        } catch (RuntimeException failure) {
            diagnosticLoginStage("IMMEDIATE_REQUEUE", ticket.ticketId(), "ERROR", "AUTH_STATE_UNAVAILABLE",
                    elapsedMillis(offerStarted), failure);
            throw failure;
        }
        Ticket latest = tickets.get(ticket.ticketId());
        String outcome = latest == null
                ? "STATE_MISSING"
                : latest.status() == TicketStatus.WAITING
                        ? "QUEUE_OFFERED"
                        : latest.status() == TicketStatus.REQUEUE_OFFERING
                                ? "QUEUE_OFFER_IN_PROGRESS"
                                : latest.status() == TicketStatus.REQUEUING_UNKNOWN
                                        ? "QUEUE_ERROR"
                                        : latest.status().name();
        diagnosticLoginStage("IMMEDIATE_REQUEUE", ticket.ticketId(), outcome, "NONE",
                elapsedMillis(offerStarted), null);
    }

    private void resumeRequeue(IMap<String, Ticket> tickets, Ticket requeue) {
        resumeRequeue(tickets, requeue, null);
    }

    private void resumeRequeue(IMap<String, Ticket> tickets, Ticket requeue,
            ReconciliationAccumulator observations) {
        Ticket offering = requeue.withState(TicketStatus.REQUEUE_OFFERING, null, null, null,
                Instant.now().plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), null, null, null);
        if (!replaceForReconciliation(tickets, requeue, offering, ReconciliationStage.CLAIM_OFFERING,
                observations)) {
            return;
        }
        boolean offered;
        try {
            offered = offerQueueToken(new QueueToken(requeue.ticketId(), requeue.enqueueGeneration()));
        } catch (AppendFencedException exception) {
            if (observations != null) {
                observations.offer(ReconciliationOutcome.APPEND_FENCED);
            }
            replaceForReconciliation(tickets, offering,
                    offering.withState(TicketStatus.REQUEUING, null, null, null, null, null, null, null),
                    ReconciliationStage.OFFER_RETRY, observations);
            return;
        } catch (RuntimeException exception) {
            if (observations != null) {
                observations.offer(ReconciliationOutcome.ERROR);
                observations.diagnostic(ReconciliationStage.OFFER, exception);
            }
            replaceForReconciliation(tickets, offering,
                    offering.withState(TicketStatus.REQUEUING_UNKNOWN,
                            null, null, null, Instant.now().plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), null, null,
                            null),
                    ReconciliationStage.OFFER_UNKNOWN, observations);
            return;
        }
        if (observations != null) {
            observations.offer(offered ? ReconciliationOutcome.OFFERED : ReconciliationOutcome.QUEUE_FULL);
        }
        if (offered) {
            boolean waiting;
            try {
                waiting = replaceForReconciliation(tickets, offering,
                        offering.withState(TicketStatus.WAITING, null, null, null, null, null, null, null),
                        ReconciliationStage.OFFER_WAITING, observations);
            } catch (RuntimeException exception) {
                replaceForReconciliation(tickets, offering,
                        offering.withState(TicketStatus.REQUEUING_UNKNOWN,
                                null, null, null, Instant.now().plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), null,
                                null, null),
                        ReconciliationStage.OFFER_UNKNOWN, observations);
                return;
            }
            if (waiting) {
                QueueToken token = new QueueToken(requeue.ticketId(), requeue.enqueueGeneration());
                try {
                    clearAppendFenceAfterRequeue(token, observations);
                } catch (RuntimeException cleanupFailure) {
                    if (observations == null) {
                        replaceForReconciliation(tickets, offering,
                                offering.withState(TicketStatus.REQUEUING_UNKNOWN,
                                        null, null, null,
                                        Instant.now().plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), null, null, null),
                                ReconciliationStage.OFFER_UNKNOWN, null);
                    }
                }
            }
        } else {
            try {
                replaceForReconciliation(tickets, offering,
                        offering.withState(TicketStatus.REQUEUING, null, null, null, null, null, null, null),
                        ReconciliationStage.OFFER_RETRY, observations);
            } catch (RuntimeException exception) {
                replaceForReconciliation(tickets, offering,
                        offering.withState(TicketStatus.REQUEUING_UNKNOWN,
                                null, null, null, Instant.now().plusSeconds(OPERATION_CLAIM_LEASE_SECONDS), null,
                                null, null),
                        ReconciliationStage.OFFER_UNKNOWN, observations);
            }
        }
    }

    private boolean replaceForReconciliation(IMap<String, Ticket> tickets, Ticket current, Ticket next,
            ReconciliationStage stage, ReconciliationAccumulator observations) {
        try {
            boolean replaced = replaceTicketState(tickets, current, next);
            if (observations != null) {
                observations.transition(stage,
                        replaced ? ReconciliationOutcome.SUCCESS : ReconciliationOutcome.MISS);
            }
            return replaced;
        } catch (RuntimeException exception) {
            if (observations != null) {
                observations.transition(stage, ReconciliationOutcome.ERROR);
                observations.diagnostic(stage, exception);
            }
            throw exception;
        }
    }

    boolean replaceTicketState(IMap<String, Ticket> tickets, Ticket current, Ticket next) {
        return facadeHooks.replaceTicketState(tickets, current, next);
    }

    public boolean doReplaceTicketState(IMap<String, Ticket> tickets, Ticket current, Ticket next) {
        return tickets.replace(current.ticketId(), current, next);
    }

    void clearAppendFenceAfterRequeue(QueueToken token, ReconciliationAccumulator observations) {
        try {
            clearAppendFence(token);
        } catch (RuntimeException exception) {
            if (observations != null) {
                observations.diagnostic(ReconciliationStage.APPEND_FENCE_CLEAR, exception);
                return;
            }
            throw exception;
        }
    }

    boolean offerQueueToken(QueueToken token) {
        return facadeHooks.offerQueueToken(token);
    }

    public boolean doOfferQueueToken(QueueToken token) {
        HazelcastInstance hz = requireHazelcast();
        IMap<String, QueueOfferFence> fences = hz.getMap(APPEND_FENCES);
        QueueOfferFence fence = new QueueOfferFence(token,
                Instant.now().plusSeconds(OPERATION_CLAIM_LEASE_SECONDS));
        if (fences.putIfAbsent("active", fence) != null) {
            throw new AppendFencedException();
        }
        boolean offered;
        try {
            offered = hz.<QueueToken>getQueue(ORDER).offer(token);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (!offered) {
            fences.remove("active", fence);
        }
        return offered;
    }

    void clearAppendFence(QueueToken token) {
        facadeHooks.clearAppendFence(token);
    }

    public void doClearAppendFence(QueueToken token) {
        IMap<String, QueueOfferFence> fences = requireHazelcast().getMap(APPEND_FENCES);
        QueueOfferFence fence = fences.get("active");
        if (fence != null && fence.token().equals(token)) {
            fences.remove("active", fence);
        }
    }

    private void resolveAppendFence(HazelcastInstance hz, IMap<String, Ticket> tickets, IQueue<QueueToken> order) {
        IMap<String, QueueOfferFence> fences = hz.getMap(APPEND_FENCES);
        QueueOfferFence fence = fences.get("active");
        if (fence == null) {
            return;
        }
        QueueToken token = fence.token();
        if (order.contains(token)) {
            Ticket ticket = tickets.get(token.ticketId());
            boolean resolved = ticket == null || ticket.enqueueGeneration() != token.generation()
                    || isTerminal(ticket.status());
            if (ticket != null && ticket.enqueueGeneration() == token.generation()) {
                if (ticket.status() == TicketStatus.CREATING) {
                    Ticket waiting = ticket.withState(TicketStatus.WAITING, null, null, null, null,
                            null, null, null);
                    if (tickets.replace(ticket.ticketId(), ticket, waiting)) {
                        IMap<String, CreateOperation> operations = hz.getMap(CREATE_OPS);
                        CreateOperation operation = operations.get(ticket.createOperationId());
                        resolved = operation != null && (operation.status() == CreateOperationStatus.ACCEPTED
                                || (operation.status() == CreateOperationStatus.OFFERING
                                        || operation.status() == CreateOperationStatus.PENDING)
                                        && operations.replace(ticket.createOperationId(), operation,
                                                operation.withStatus(CreateOperationStatus.ACCEPTED, Instant.now())));
                    }
                } else if (ticket.status() == TicketStatus.REQUEUE_OFFERING) {
                    resolved = tickets.replace(ticket.ticketId(), ticket,
                            ticket.withState(TicketStatus.WAITING, null, null, null, null, null, null, null));
                } else if (ticket.status() == TicketStatus.WAITING) {
                    IMap<String, CreateOperation> operations = hz.getMap(CREATE_OPS);
                    CreateOperation operation = operations.get(ticket.createOperationId());
                    resolved = operation != null && (operation.status() == CreateOperationStatus.ACCEPTED
                            || (operation.status() == CreateOperationStatus.OFFERING
                                    || operation.status() == CreateOperationStatus.PENDING)
                                    && operations.replace(ticket.createOperationId(), operation,
                                            operation.withStatus(CreateOperationStatus.ACCEPTED, Instant.now())));
                } else if (isCreateProgressed(ticket.status())) {
                    IMap<String, CreateOperation> operations = hz.getMap(CREATE_OPS);
                    CreateOperation operation = operations.get(ticket.createOperationId());
                    resolved = operation != null && (operation.status() == CreateOperationStatus.ACCEPTED
                            || (operation.status() == CreateOperationStatus.OFFERING
                                    || operation.status() == CreateOperationStatus.PENDING)
                                    && operations.replace(ticket.createOperationId(), operation,
                                            operation.withStatus(CreateOperationStatus.ACCEPTED, Instant.now())));
                } else {
                    resolved = ticket.status() != TicketStatus.CREATING;
                }
            }
            if (resolved) {
                fences.remove("active", fence);
            }
            return;
        }
        if (!fence.deadline().isAfter(Instant.now())) {
            Ticket ticket = tickets.get(token.ticketId());
            if (ticket != null && ticket.enqueueGeneration() == token.generation()
                    && ticket.status() == TicketStatus.REQUEUE_OFFERING) {
                expireTicket(tickets, ticket);
            }
            fences.remove("active", fence);
        }
    }

    private void reserveOperationRecord() {
        while (true) {
            int current = operationRecordCount.get();
            if (current >= properties.operationRecordCapacity()) {
                throw new AuthStateUnavailableException();
            }
            if (operationRecordCount.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    private void releaseOperationRecord() {
        while (true) {
            int current = operationRecordCount.get();
            if (current <= 0) {
                throw new AuthStateUnavailableException();
            }
            if (operationRecordCount.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    private long operationRetentionSeconds() {
        return properties.idempotencyIdleHorizonSeconds() + properties.queueTicketSeconds()
                + properties.queueTombstoneSeconds();
    }

    private long bindingRetentionSeconds() {
        return operationRetentionSeconds();
    }

    private boolean putOrRefreshBinding(IMap<String, String> bindings, String cookieHash, String ticketId) {
        String prior = bindings.putIfAbsent(cookieHash, ticketId, bindingRetentionSeconds(), TimeUnit.SECONDS);
        return prior == null || prior.equals(ticketId);
    }

    private void refreshBindingTtl(IMap<String, String> bindings, String cookieHash, String ticketId) {
        String current = bindings.get(cookieHash);
        if (current != null && !ticketId.equals(current)) {
            throw new AuthStateUnavailableException();
        }
    }

    private String resolveBoundTicketId(IMap<String, String> bindings, String cookieHash) {
        return bindings.get(cookieHash);
    }

    private void setOperationRetention(IMap<String, CreateOperation> operations, String operationId) {
        if (!operations.setTtl(operationId, operationRetentionSeconds(), TimeUnit.SECONDS)) {
            throw new AuthStateUnavailableException();
        }
    }

    private void cleanupExpiredOperationRecords(IMap<String, CreateOperation> operations,
            IMap<String, Ticket> tickets) {
        Instant cutoff = Instant.now().minusSeconds(properties.idempotencyIdleHorizonSeconds());
        for (Map.Entry<String, CreateOperation> entry : boundedEntries(operations, RECOVERY_BATCH_SIZE)) {
            CreateOperation operation = entry.getValue();
            if (operation.lastRequestedAt() != null && operation.lastRequestedAt().isBefore(cutoff)) {
                Ticket ticket = operation.ticketId() == null ? null : tickets.get(operation.ticketId());
                if (ticket == null || isTerminal(ticket.status())) {
                    if (operations.remove(entry.getKey(), operation)) {
                        releaseOperationRecord();
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <K, V> List<Map.Entry<K, V>> boundedEntries(IMap<K, V> map, int limit) {
        java.util.ArrayList<Map.Entry<K, V>> result = new java.util.ArrayList<>(limit);
        java.util.Iterator<Map.Entry<K, V>> iterator = (java.util.Iterator<Map.Entry<K, V>>) recoveryIterators
                .computeIfAbsent(map.getName(), ignored -> map.iterator(limit));
        int inspected = 0;
        while (iterator.hasNext() && inspected < limit) {
            inspected++;
            Map.Entry<K, V> entry = iterator.next();
            if (entry != null && entry.getValue() != null) {
                result.add(entry);
            }
        }
        if (!iterator.hasNext()) {
            recoveryIterators.remove(map.getName());
        }
        return result;
    }

    private boolean reserveActiveTicket(String ticketId, String reservationId) {
        return admissionCoordinator.reserveActiveTicket(ticketId, reservationId);
    }

    private boolean transferActiveTicketReservation(String ticketId, String previousReservationId,
            String nextReservationId) {
        return admissionCoordinator.transferActiveTicketReservation(ticketId, previousReservationId,
                nextReservationId);
    }

    private void releaseActiveTicket(String ticketId) {
        admissionCoordinator.releaseActiveTicket(ticketId);
    }

    private boolean releaseActiveTicket(String ticketId, String reservationId) {
        return admissionCoordinator.releaseActiveTicket(ticketId, reservationId);
    }

    public synchronized void restoreActiveTicketReservations() {
        recoveryCoordinator.restoreStartupState(new AuthQueueRecoveryCoordinator.StartupRecoveryAccess() {
            @Override
            public void restoreSessionCount() {
                facadeHooks.restoreSessionCount(requireHazelcast().getMap(SESSIONS));
            }

            @Override
            public AuthQueueRecoveryCoordinator.StartupReservationSnapshot scanActiveReservations() {
                return scanStartupReservations();
            }

            @Override
            public void setRestoredCounters(long operationCount, long maxOrder) {
                operationRecordCount.set(Math.toIntExact(operationCount));
                ticketOrderSequence.set(maxOrder);
            }
        });
    }

    private AuthQueueRecoveryCoordinator.StartupReservationSnapshot scanStartupReservations() {
        HazelcastInstance hz = requireHazelcast();
        IMap<String, Ticket> tickets = hz.getMap(TICKETS);
        IMap<String, CreateOperation> operations = hz.getMap(CREATE_OPS);
        IMap<String, AuthSession> sessions = hz.getMap(SESSIONS);
        Map<String, String> active = new HashMap<>();
        pendingCreateOperations.clear();
        long maxOrder = 0;
        java.util.Iterator<Map.Entry<String, Ticket>> iterator = tickets.iterator(RECOVERY_BATCH_SIZE);
        long limit = properties.maxActiveTickets();
        while (iterator.hasNext()) {
            Map.Entry<String, Ticket> entry = iterator.next();
            Ticket ticket = entry.getValue();
            if (ticket == null) {
                continue;
            }
            if (ticket.status() == TicketStatus.VERIFYING && isClaimLeaseExpired(ticket, Instant.now())
                    && !ownsBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey())) {
                recoveryCoordinator.recoverExpiredClaim(tickets, ticket, sessions);
                ticket = tickets.get(entry.getKey());
                if (ticket == null) {
                    continue;
                }
            }
            if (isTicketExpired(ticket, Instant.now())) {
                expireTicket(tickets, ticket);
                CreateOperation operation = operations.get(ticket.createOperationId());
                if (operation != null && operation.status() != CreateOperationStatus.TERMINAL) {
                    operations.replace(ticket.createOperationId(), operation,
                            operation.withStatus(CreateOperationStatus.TERMINAL, Instant.now()));
                }
                ticket = tickets.get(entry.getKey());
                if (ticket == null) {
                    continue;
                }
            }
            if (ticket.status() == TicketStatus.READY && ticket.bcryptPermitKey() != null) {
                Ticket readyWithoutLegacyPermit = ticket.withState(TicketStatus.READY, ticket.loginOutcome(),
                        ticket.loginOperationId(), ticket.claimId(), ticket.claimLeaseUntil(),
                        ticket.readyExpiresAt(), null, ticket.sid());
                if (tickets.replace(ticket.ticketId(), ticket, readyWithoutLegacyPermit)) {
                    ticket = readyWithoutLegacyPermit;
                } else {
                    ticket = tickets.get(entry.getKey());
                    if (ticket == null) {
                        continue;
                    }
                }
            }
            if (ticket.status() == TicketStatus.ADMITTING) {
                Ticket waiting = ticket.withState(TicketStatus.WAITING, null, null, null, null,
                        null, null, null);
                if (tickets.replace(ticket.ticketId(), ticket, waiting)) {
                    ticket = waiting;
                } else {
                    ticket = tickets.get(entry.getKey());
                    if (ticket == null) {
                        continue;
                    }
                }
            }
            maxOrder = Math.max(maxOrder, ticket.order());
            if (isActive(ticket.status())) {
                active.putIfAbsent(entry.getKey(), "restored-ticket:" + entry.getKey());
            }
            if (active.size() > limit) {
                throw new AuthStateUnavailableException();
            }
        }
        var operationIterator = operations.iterator(RECOVERY_BATCH_SIZE);
        long operationCount = 0;
        while (operationIterator.hasNext()) {
            Map.Entry<String, CreateOperation> operationEntry = operationIterator.next();
            CreateOperation operation = operationEntry.getValue();
            operationCount++;
            if (operationCount > properties.operationRecordCapacity()) {
                throw new AuthStateUnavailableException();
            }
            if (operation.ticketId() != null && (operation.status() == CreateOperationStatus.CLAIMED
                    || operation.status() == CreateOperationStatus.PENDING
                    || operation.status() == CreateOperationStatus.OFFERING)) {
                Ticket ticket = tickets.get(operation.ticketId());
                if (ticket == null || isActive(ticket.status())) {
                    active.putIfAbsent(operation.ticketId(), operation.reservationId() == null
                            ? "restored-ticket:" + operation.ticketId()
                            : operation.reservationId());
                    if (operation.reservationId() != null) {
                        active.put(operation.ticketId(), operation.reservationId());
                    }
                    if (active.size() > limit) {
                        throw new AuthStateUnavailableException();
                    }
                }
            }
            if (operation.status() == CreateOperationStatus.PENDING && operation.ticketId() != null) {
                Ticket ticket = tickets.get(operation.ticketId());
                if (ticket != null && ticket.status() == TicketStatus.CREATING) {
                    long order = ticket.order();
                    if (order <= 0) {
                        order = ++maxOrder;
                    } else {
                        maxOrder = Math.max(maxOrder, order);
                    }
                    pendingCreateOperations.put(order, operationEntry.getKey());
                }
            }
        }
        return new AuthQueueRecoveryCoordinator.StartupReservationSnapshot(active, maxOrder, operationCount);
    }

    private void terminalizeCreate(IMap<String, Ticket> tickets, IMap<String, CreateOperation> operations,
            Ticket ticket, CreateOperation expectedOperation) {
        Ticket expired = ticket.withState(TicketStatus.EXPIRED, null, null, null, null,
                null, null, null);
        if (!tickets.replace(ticket.ticketId(), ticket, expired)) {
            return;
        }
        tickets.set(ticket.ticketId(), expired, properties.queueTombstoneSeconds(), TimeUnit.SECONDS);
        if (expectedOperation != null) {
            CreateOperation terminal = expectedOperation.withStatus(CreateOperationStatus.TERMINAL, Instant.now());
            operations.replace(ticket.createOperationId(), expectedOperation, terminal);
        }
        releaseActiveTicket(ticket.ticketId());
    }

    private boolean isTerminal(TicketStatus status) {
        return status == TicketStatus.CONSUMED || status == TicketStatus.CANCELLED || status == TicketStatus.EXPIRED;
    }

    public void expireTicket(IMap<String, Ticket> tickets, Ticket ticket) {
        Ticket expired = ticket.withState(TicketStatus.EXPIRED, null, null, null, null,
                null, ticket.bcryptPermitKey(), null);
        if (tickets.replace(ticket.ticketId(), ticket, expired)) {
            tickets.set(ticket.ticketId(), expired, properties.queueTombstoneSeconds(), TimeUnit.SECONDS);
            requireHazelcast().<QueueToken>getQueue(ORDER)
                    .remove(new QueueToken(ticket.ticketId(), ticket.enqueueGeneration()));
            releaseBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey());
            releaseActiveTicket(ticket.ticketId());
        }
    }

    private boolean isTicketExpired(Ticket ticket, Instant now) {
        if (ticket.status() == TicketStatus.READY) {
            return !ticket.expiresAt().isAfter(now)
                    || ticket.readyExpiresAt() != null && !ticket.readyExpiresAt().isAfter(now);
        }
        if (ticket.status() == TicketStatus.VERIFYING) {
            return false;
        }
        return !isTerminal(ticket.status()) && !ticket.expiresAt().isAfter(now);
    }

    private void enforceStatusReadLimit(String ticketId) {
        enforceRateLimit(ticketId, "status");
    }

    private void enforceRateLimit(String ticketId, String purpose) {
        IMap<String, Long> reads = requireHazelcast().getMap("login-queue-status-rate-v2");
        String key = hmac(purpose + ":" + ticketId);
        long now = System.currentTimeMillis();
        Long prior = reads.putIfAbsent(key, now, properties.statusMinimumIntervalSeconds(), TimeUnit.SECONDS);
        if (prior == null) {
            return;
        }
        if (prior + TimeUnit.SECONDS.toMillis(properties.statusMinimumIntervalSeconds()) > now) {
            throw new AuthStateTooManyRequestsException("RATE_LIMITED");
        }
        if (!reads.replace(key, prior, now)) {
            throw new AuthStateTooManyRequestsException("RATE_LIMITED");
        }
    }

    private boolean isReusable(Ticket ticket) {
        return !isTerminal(ticket.status())
                && ticket.expiresAt().isAfter(Instant.now());
    }

    private boolean isActive(TicketStatus status) {
        return status != TicketStatus.CONSUMED && status != TicketStatus.CANCELLED && status != TicketStatus.EXPIRED;
    }

    private HazelcastInstance requireHazelcast() {
        return stateAccess.requireHazelcast();
    }

    private boolean isClaimLeaseExpired(Ticket ticket, Instant now) {
        return ticket.claimLeaseUntil() != null && !ticket.claimLeaseUntil().isAfter(now);
    }

    public String hmac(String value) {
        String key = properties.hmacKey();
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new AuthStateUnavailableException();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedNanos));
    }

    private String cookieForOperation(String operationId) {
        return hmac("queue-cookie:" + operationId);
    }

    private void validateUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new AuthStateConflictException("INVALID_OPERATION_ID");
        }
    }

    private RuntimeException unavailable(RuntimeException exception) {
        if (exception instanceof AuthStateUnavailableException) {
            return exception;
        }
        return new AuthStateUnavailableException(exception);
    }

    public AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt) {
        return createSession(userId, sid, currentJti, refreshHash, loginOperationId, encryptedRefreshToken,
                accessExpiresAt, refreshExpiresAt, "Unknown device");
    }

    public synchronized AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt, String userAgentSummary) {
        return createSession(userId, sid, currentJti, refreshHash, loginOperationId, encryptedRefreshToken,
                accessExpiresAt, refreshExpiresAt, userAgentSummary, null, null, null);
    }

    public synchronized AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt, String userAgentSummary, String ticketId, String ownerClaimId,
            Instant provisioningLeaseUntil) {
        return sessionStore.createSession(userId, sid, currentJti, refreshHash, loginOperationId,
                encryptedRefreshToken, accessExpiresAt, refreshExpiresAt, userAgentSummary, ticketId, ownerClaimId,
                provisioningLeaseUntil);
    }

    private boolean isLoginRecoveryValid(AuthSession session, Instant now) {
        return sessionStore.isLoginRecoveryValid(session, now);
    }

    public void assertLoginClaim(String ticketId, String operationId, String claimId, Long userId) {
        Ticket current = requireHazelcast().<String, Ticket>getMap(TICKETS).get(ticketId);
        if (current == null || current.status() != TicketStatus.VERIFYING
                || !java.util.Objects.equals(current.loginOperationId(), operationId)
                || !java.util.Objects.equals(current.claimId(), claimId)
                || !java.util.Objects.equals(current.authenticatedUserId(), userId)) {
            throw new AuthStateUnavailableException();
        }
    }

    public void doIndexLoginSession(HazelcastInstance hz, String loginOperationId, String sid,
            Instant refreshExpiresAt) {
        hz.<String, String>getMap(LOGIN_SESSION_INDEX).put(loginOperationId, sid,
                ttlSeconds(refreshExpiresAt), TimeUnit.SECONDS);
    }

    public void doPromoteUserSlot(Long userId, String sid) {
        userSessionIndexStore.promote(userId, sid);
    }

    public void doRestoreSessionCount(IMap<String, AuthSession> sessions) {
        sessionStore.restoreSessionCount(sessions);
    }

    private void releaseSessionCapacity(String sid) {
        sessionStore.releaseSessionCapacity(sid);
    }

    private void releaseUserSlot(Long userId, String sid) {
        userSessionIndexStore.release(userId, sid);
    }

    public AuthSession getSession(String sid) {
        return sessionStore.getSession(sid);
    }

    public boolean replaceSession(AuthSession expected, AuthSession updated) {
        return sessionStore.replaceSession(expected, updated);
    }

    public AuthSession revokeSession(String sid, Long expectedUserId) {
        return sessionStore.revokeSession(sid, expectedUserId);
    }

    public List<AuthSession> activeSessions(Long userId) {
        return sessionStore.activeSessions(userId);
    }

    public String tokenHash(String token) {
        return sessionStore.tokenHash(token);
    }

    public String jtiHash(String jti) {
        return sessionStore.jtiHash(jti);
    }

    public Instant newAbsoluteSessionExpiry() {
        return sessionStore.newAbsoluteSessionExpiry();
    }

    public Instant nextRefreshIdleExpiry(Instant absoluteExpiry) {
        return sessionStore.nextRefreshIdleExpiry(absoluteExpiry);
    }

    @SuppressWarnings("unchecked")
    public <K, V> List<V> boundedValues(IMap<K, V> map, int limit) {
        return boundedValues(map, limit, "default");
    }

    @SuppressWarnings("unchecked")
    public <K, V> List<V> boundedValues(IMap<K, V> map, int limit, String cursorName) {
        String cursorKey = map.getName() + ":" + cursorName;
        java.util.ArrayList<V> result = new java.util.ArrayList<>(limit);
        java.util.Iterator<Map.Entry<K, V>> iterator = (java.util.Iterator<Map.Entry<K, V>>) recoveryIterators
                .computeIfAbsent(cursorKey, ignored -> map.iterator(limit));
        int inspected = 0;
        while (iterator.hasNext() && inspected < limit) {
            inspected++;
            Map.Entry<K, V> entry = iterator.next();
            if (entry != null && entry.getValue() != null) {
                result.add(entry.getValue());
            }
        }
        if (!iterator.hasNext()) {
            recoveryIterators.remove(cursorKey);
        }
        return result;
    }
    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private long ttlSeconds(Instant expiry) {
        return Math.max(1, Duration.between(Instant.now(), expiry).toSeconds());
    }
}
