package com.muse.meomuneum.auth.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateExpiredException;
import com.muse.meomuneum.auth.exception.AuthStateNotFoundException;
import com.muse.meomuneum.auth.exception.AuthStateTooEarlyException;
import com.muse.meomuneum.auth.exception.AuthStateTooManyRequestsException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.AuthSession;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.CreateOperation;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.LoginOutcome;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.SessionStatus;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.Ticket;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore.TicketStatus;

/** Owns FIFO selection and conditional ticket transitions into READY. */
final class HazelcastLoginQueueTicketTransitions {
    private static final String TICKETS = "login-queue-tickets-v2";
    private static final String BINDINGS = "login-queue-cookie-bindings-v2";
    private static final String ORDER = "login-queue-order-v2";
    private static final String SESSIONS = "auth-sessions-v2";
    private static final String CREATE_OPS = "login-queue-create-operations-v2";
    private static final String CANCEL_FENCES = "login-queue-ticket-cancel-fences-v1";
    private static final int RECOVERY_BATCH_SIZE = 100;
    private static final long TERMINAL_OBSERVATION_SECONDS = 900;

    interface Operations {
        HazelcastInstance requireHazelcast();

        void resolveAppendFence(HazelcastInstance hz, IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                IQueue<HazelcastAuthStateStore.QueueToken> order);

        boolean isTicketExpired(HazelcastAuthStateStore.Ticket ticket, Instant now);

        void expireTicket(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                HazelcastAuthStateStore.Ticket ticket);

        Optional<String> reserveBcryptPermit(String ticketId);

        void releaseBcryptPermit(String owner, String key);

        void diagnosticEvent(String event, String ticketId, String status);

        String hmac(String value);

        String resolveBoundTicketId(IMap<String, String> bindings, String cookieHash);

        void diagnosticLoginStage(String stage, String ticketId, String outcome, String errorCode,
                long durationMillis, Throwable failure);

        boolean isClaimLeaseExpired(HazelcastAuthStateStore.Ticket ticket, Instant now);

        boolean ownsBcryptPermit(String owner, String key);

        boolean recoverExpiredClaim(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                HazelcastAuthStateStore.Ticket ticket, IMap<String, HazelcastAuthStateStore.AuthSession> sessions);

        void enforceStatusReadLimit(String ticketId);

        HazelcastAuthStateStore.TicketView ticketView(HazelcastAuthStateStore.Ticket ticket);

        void releaseActiveTicket(String ticketId);

        boolean ownsClaim(HazelcastAuthStateStore.Ticket current, HazelcastAuthStateStore.Ticket claim);

        long elapsedMillis(long startedNanos);

        RuntimeException unavailable(RuntimeException exception);

        void validateUuid(String value);

        String diagnosticCorrelationId(String ticketId);

        void diagnosticLoginRequestStage(String stage, String correlationId, String outcome, String errorCode,
                long durationMillis, Throwable failure);

        void requeueReadyTicket(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                HazelcastAuthStateStore.Ticket ticket);

        String localBcryptPermitKey(String ticketId);

        long bcryptPermitLeaseSeconds();

        void resumeRequeue(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
                HazelcastAuthStateStore.Ticket ticket);

        boolean isLoginRecoveryValid(HazelcastAuthStateStore.AuthSession session, Instant now);
    }

    private final AuthStateProperties properties;
    private final LoginQueueAdmissionCoordinator admissionCoordinator;
    private final Operations operations;
    private final java.util.function.BooleanSupplier reservePermitOnReady;

    HazelcastLoginQueueTicketTransitions(AuthStateProperties properties,
            LoginQueueAdmissionCoordinator admissionCoordinator, Operations operations,
            java.util.function.BooleanSupplier reservePermitOnReady) {
        this.properties = properties;
        this.admissionCoordinator = admissionCoordinator;
        this.operations = operations;
        this.reservePermitOnReady = reservePermitOnReady;
    }

    Optional<HazelcastAuthStateStore.Ticket> nextWaitingTicket() {
        HazelcastInstance hz = operations.requireHazelcast();
        IQueue<HazelcastAuthStateStore.QueueToken> order = hz.getQueue(ORDER);
        IMap<String, HazelcastAuthStateStore.Ticket> tickets = hz.getMap(TICKETS);
        for (int inspected = 0; inspected < RECOVERY_BATCH_SIZE; inspected++) {
            operations.resolveAppendFence(hz, tickets, order);
            HazelcastAuthStateStore.QueueToken token = order.peek();
            if (token == null) {
                return Optional.empty();
            }
            HazelcastAuthStateStore.Ticket ticket = tickets.get(token.ticketId());
            if (ticket == null) {
                order.remove(token);
                continue;
            }
            HazelcastAuthStateStore.TicketStatus status = ticket.status();
            if (status == HazelcastAuthStateStore.TicketStatus.CREATING
                    || status == HazelcastAuthStateStore.TicketStatus.OFFERING
                    || status == HazelcastAuthStateStore.TicketStatus.PENDING
                    || status == HazelcastAuthStateStore.TicketStatus.ADMITTING
                    || status == HazelcastAuthStateStore.TicketStatus.REQUEUE_OFFERING
                    || status == HazelcastAuthStateStore.TicketStatus.REQUEUING_UNKNOWN) {
                return Optional.empty();
            }
            if (ticket.enqueueGeneration() != token.generation()) {
                order.remove(token);
                continue;
            }
            if (status == HazelcastAuthStateStore.TicketStatus.WAITING) {
                if (operations.isTicketExpired(ticket, Instant.now())) {
                    operations.expireTicket(tickets, ticket);
                    order.remove(token);
                    continue;
                }
                return Optional.of(ticket);
            }
            order.remove(token);
        }
        return Optional.empty();
    }

    HazelcastAuthStateStore.TicketView currentTicket(String queueCookie) {
        HazelcastInstance hz = operations.requireHazelcast();
        if (queueCookie == null || queueCookie.isBlank()) {
            throw new AuthStateNotFoundException("TICKET_UNKNOWN");
        }
        IMap<String, HazelcastAuthStateStore.Ticket> tickets = hz.getMap(TICKETS);
        String cookieHash = operations.hmac("cookie:" + queueCookie);
        String ticketId = operations.resolveBoundTicketId(hz.getMap("login-queue-cookie-bindings-v2"), cookieHash);
        HazelcastAuthStateStore.Ticket ticket = ticketId == null ? null : tickets.get(ticketId);
        if (ticket == null && ticketId != null) {
            CreateOperation operation = hz.<String, CreateOperation>getMap("login-queue-create-operations-v2")
                    .get(ticketId);
            if (operation != null && ticketId.equals(operation.ticketId())
                    && (operation.status() == HazelcastAuthStateStore.CreateOperationStatus.CLAIMED
                            || operation.status() == HazelcastAuthStateStore.CreateOperationStatus.PENDING
                            || operation.status() == HazelcastAuthStateStore.CreateOperationStatus.OFFERING)) {
                operations.enforceStatusReadLimit(ticketId);
                return new HazelcastAuthStateStore.TicketView(HazelcastAuthStateStore.TicketStatus.WAITING, null,
                        null, 6, ticketId);
            }
        }
        if (ticket == null) {
            throw new AuthStateNotFoundException("TICKET_UNKNOWN");
        }
        operations.diagnosticLoginStage("TICKET_RESOLUTION", ticketId, "RESOLVED", "NONE", 0, null);
        if (ticket.status() == HazelcastAuthStateStore.TicketStatus.VERIFYING
                && operations.isClaimLeaseExpired(ticket, Instant.now())
                && !operations.ownsBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey())) {
            long recoveryStarted = System.nanoTime();
            boolean recovered;
            try {
                recovered = operations.recoverExpiredClaim(tickets, ticket, hz.getMap("auth-sessions-v2"));
                operations.diagnosticLoginStage("EXPIRED_CLAIM_RECOVERY", ticketId,
                        recovered ? "CAS_SUCCESS" : "CAS_MISS", "NONE",
                        operations.elapsedMillis(recoveryStarted), null);
            } catch (RuntimeException failure) {
                operations.diagnosticLoginStage("EXPIRED_CLAIM_RECOVERY", ticketId, "ERROR",
                        "AUTH_STATE_UNAVAILABLE", operations.elapsedMillis(recoveryStarted), failure);
                throw operations.unavailable(failure);
            }
            ticket = tickets.get(ticketId);
            if (ticket == null) {
                throw new AuthStateNotFoundException("TICKET_UNKNOWN");
            }
        }
        operations.enforceStatusReadLimit(ticketId);
        if (ticket.status() != HazelcastAuthStateStore.TicketStatus.VERIFYING
                && operations.isTicketExpired(ticket, Instant.now())) {
            operations.expireTicket(tickets, ticket);
            throw new AuthStateExpiredException();
        }
        return operations.ticketView(ticket);
    }

    private HazelcastInstance requireHazelcast() {
        return operations.requireHazelcast();
    }

    private String resolveBoundTicketId(IMap<String, String> bindings, String cookieHash) {
        return operations.resolveBoundTicketId(bindings, cookieHash);
    }

    private String hmac(String value) {
        return operations.hmac(value);
    }

    private void validateUuid(String value) {
        operations.validateUuid(value);
    }

    private String diagnosticCorrelationId(String ticketId) {
        return operations.diagnosticCorrelationId(ticketId);
    }

    private void diagnosticLoginRequestStage(String stage, String correlationId, String outcome, String errorCode,
            long durationMillis, Throwable failure) {
        operations.diagnosticLoginRequestStage(stage, correlationId, outcome, errorCode, durationMillis, failure);
    }

    private long bcryptPermitLeaseSeconds() {
        return operations.bcryptPermitLeaseSeconds();
    }

    private long elapsedMillis(long startedNanos) {
        return operations.elapsedMillis(startedNanos);
    }

    private boolean isClaimLeaseExpired(Ticket ticket, Instant now) {
        return operations.isClaimLeaseExpired(ticket, now);
    }

    private boolean ownsBcryptPermit(String owner, String key) {
        return operations.ownsBcryptPermit(owner, key);
    }

    private boolean recoverExpiredClaim(IMap<String, Ticket> tickets, Ticket ticket,
            IMap<String, AuthSession> sessions) {
        return operations.recoverExpiredClaim(tickets, ticket, sessions);
    }

    private boolean isTicketExpired(Ticket ticket, Instant now) {
        return operations.isTicketExpired(ticket, now);
    }

    private void expireTicket(IMap<String, Ticket> tickets, Ticket ticket) {
        operations.expireTicket(tickets, ticket);
    }

    private void diagnosticLoginStage(String stage, String ticketId, String outcome, String errorCode,
            long durationMillis, Throwable failure) {
        operations.diagnosticLoginStage(stage, ticketId, outcome, errorCode, durationMillis, failure);
    }

    private void diagnosticEvent(String event, String ticketId, String status) {
        operations.diagnosticEvent(event, ticketId, status);
    }

    private RuntimeException unavailable(RuntimeException exception) {
        return operations.unavailable(exception);
    }

    private void requeueReadyTicket(IMap<String, Ticket> tickets, Ticket ticket) {
        operations.requeueReadyTicket(tickets, ticket);
    }

    private void resumeRequeue(IMap<String, Ticket> tickets, Ticket ticket) {
        operations.resumeRequeue(tickets, ticket);
    }

    private boolean isLoginRecoveryValid(AuthSession session, Instant now) {
        return operations.isLoginRecoveryValid(session, now);
    }

    private boolean ownsClaim(Ticket current, Ticket claim) {
        return operations.ownsClaim(current, claim);
    }

    private void releaseActiveTicket(String ticketId) {
        operations.releaseActiveTicket(ticketId);
    }

    private Optional<String> reserveBcryptPermit(String ticketId) {
        return operations.reserveBcryptPermit(ticketId);
    }

    private void releaseBcryptPermit(String owner, String key) {
        operations.releaseBcryptPermit(owner, key);
    }

    void cancelCurrentTicket(String queueCookie) {
        HazelcastInstance hz = operations.requireHazelcast();
        String ticketId = operations.resolveBoundTicketId(hz.getMap("login-queue-cookie-bindings-v2"),
                operations.hmac("cookie:" + queueCookie));
        if (ticketId == null) {
            return;
        }
        cancelTicketById(ticketId);
    }

    void cancelTicketById(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return;
        }
        HazelcastInstance hz = operations.requireHazelcast();
        IMap<String, HazelcastAuthStateStore.Ticket> tickets = hz.getMap(TICKETS);
        tickets.lock(ticketId);
        try {
            hz.<String, Boolean>getMap(CANCEL_FENCES).put(ticketId, Boolean.TRUE,
                    properties.idempotencyIdleHorizonSeconds() + properties.queueTicketSeconds()
                            + properties.queueTombstoneSeconds(),
                    java.util.concurrent.TimeUnit.SECONDS);
            IMap<String, HazelcastAuthStateStore.CreateOperation> createOps = hz.getMap(CREATE_OPS);
            HazelcastAuthStateStore.CreateOperation operation = createOps.get(ticketId);
            if (operation != null
                    && operation.status() != HazelcastAuthStateStore.CreateOperationStatus.TERMINAL) {
                createOps.replace(ticketId, operation, operation.withStatus(
                        HazelcastAuthStateStore.CreateOperationStatus.TERMINAL, Instant.now()));
            }
            for (int attempt = 0; attempt < 8; attempt++) {
                HazelcastAuthStateStore.Ticket ticket = tickets.get(ticketId);
                if (ticket == null) {
                    operations.releaseActiveTicket(ticketId);
                    return;
                }
                if (ticket.status() == HazelcastAuthStateStore.TicketStatus.CANCELLED
                        || ticket.status() == HazelcastAuthStateStore.TicketStatus.EXPIRED
                        || ticket.status() == HazelcastAuthStateStore.TicketStatus.CONSUMED) {
                    operations.releaseActiveTicket(ticketId);
                    return;
                }
                if (ticket.status() == HazelcastAuthStateStore.TicketStatus.VERIFYING) {
                    throw new AuthStateConflictException("QUEUE_LOGIN_IN_PROGRESS");
                }
                HazelcastAuthStateStore.Ticket cancelled = ticket.withState(
                        HazelcastAuthStateStore.TicketStatus.CANCELLED, null, null, null, null, null, null, null);
                if (tickets.replace(ticketId, ticket, cancelled)) {
                    tickets.set(ticketId, cancelled, properties.queueTombstoneSeconds(),
                            java.util.concurrent.TimeUnit.SECONDS);
                    admissionCoordinator.releaseBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey());
                    hz.<HazelcastAuthStateStore.QueueToken>getQueue(ORDER)
                            .remove(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(),
                                    ticket.enqueueGeneration()));
                    operations.releaseActiveTicket(ticketId);
                    return;
                }
            }
            throw new AuthStateUnavailableException();
        } finally {
            tickets.unlock(ticketId);
        }
    }

    Ticket claimReadyTicket(String queueCookie, String loginOperationId) {
        return claimReadyTicket(queueCookie, loginOperationId, null, null);
    }

    Ticket claimReadyTicket(String queueCookie, String loginOperationId, String clientCorrelationId) {
        return claimReadyTicket(queueCookie, loginOperationId, clientCorrelationId, null);
    }

    Ticket claimReadyTicket(String queueCookie, String loginOperationId, String clientCorrelationId,
            String requestedIdentityHash) {
        validateUuid(loginOperationId);
        HazelcastInstance hz = requireHazelcast();
        String ticketId = resolveBoundTicketId(hz.<String, String>getMap(BINDINGS), hmac("cookie:" + queueCookie));
        if (ticketId == null) {
            throw new AuthStateNotFoundException("TICKET_UNKNOWN");
        }
        IMap<String, Ticket> tickets = hz.getMap(TICKETS);
        Ticket ticket = tickets.get(ticketId);
        if (ticket == null) {
            throw new AuthStateNotFoundException("TICKET_UNKNOWN");
        }
        diagnosticLoginStage("TICKET_RESOLUTION", ticketId, "RESOLVED", "NONE", 0, null);
        String expectedCorrelationId = diagnosticCorrelationId(ticketId);
        if (expectedCorrelationId != null && expectedCorrelationId.equals(clientCorrelationId)) {
            diagnosticEvent("LOGIN_POST_RECEIVED", ticketId, ticket.status().name());
        }
        if (ticket.loginOperationId() != null && !ticket.loginOperationId().equals(loginOperationId)) {
            diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, "REJECTED", "LOGIN_OPERATION_MISMATCH", 0,
                    null);
            throw new AuthStateConflictException("LOGIN_OPERATION_MISMATCH");
        }
        if (ticket.authIdentityHash() != null && requestedIdentityHash != null
                && !ticket.authIdentityHash().equals(requestedIdentityHash)) {
            diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, "REJECTED", "LOGIN_IDENTITY_MISMATCH", 0,
                    null);
            throw new AuthStateConflictException("LOGIN_IDENTITY_MISMATCH");
        }
        if (ticket.status() == TicketStatus.RETRYABLE) {
            if (ticket.authIdentityHash() != null && (requestedIdentityHash == null
                    || !ticket.authIdentityHash().equals(requestedIdentityHash))) {
                throw new AuthStateConflictException("LOGIN_IDENTITY_MISMATCH");
            }
            Ticket requeue = ticket.withEnqueue(0, ticket.enqueueGeneration() + 1,
                    TicketStatus.REQUEUING, null);
            if (tickets.replace(ticketId, ticket, requeue)) {
                diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, "RETRYABLE", "NONE", 0, null);
                resumeRequeue(tickets, requeue);
            } else {
                diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, "CAS_MISS", "NONE", 0, null);
            }
            throw new AuthStateTooEarlyException("QUEUE_WAITING");
        }
        if (ticket.status() == TicketStatus.VERIFYING) {
            if (isClaimLeaseExpired(ticket, Instant.now())
                    && !ownsBcryptPermit(ticket.ticketId(), ticket.bcryptPermitKey())) {
                long recoveryStarted = System.nanoTime();
                boolean recovered;
                try {
                    recovered = recoverExpiredClaim(tickets, ticket, hz.getMap(SESSIONS));
                    diagnosticLoginStage("EXPIRED_CLAIM_RECOVERY", ticketId,
                            recovered ? "CAS_SUCCESS" : "CAS_MISS", "NONE",
                            elapsedMillis(recoveryStarted), null);
                } catch (RuntimeException failure) {
                    diagnosticLoginStage("EXPIRED_CLAIM_RECOVERY", ticketId, "ERROR",
                            "AUTH_STATE_UNAVAILABLE", elapsedMillis(recoveryStarted), failure);
                    throw unavailable(failure);
                }
                ticket = tickets.get(ticketId);
                if (ticket == null) {
                    throw new AuthStateNotFoundException("TICKET_UNKNOWN");
                }
                if (ticket.status() == TicketStatus.RETRYABLE) {
                    requeueReadyTicket(tickets, ticket);
                    throw new AuthStateTooEarlyException("QUEUE_WAITING");
                }
                if (ticket.status() != TicketStatus.VERIFYING) {
                    throw new AuthStateConflictException("TICKET_NOT_READY");
                }
            }
            diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, "VERIFYING", "QUEUE_LOGIN_IN_PROGRESS", 0,
                    null);
            throw new AuthStateConflictException("QUEUE_LOGIN_IN_PROGRESS");
        }
        if (ticket.status() == TicketStatus.WAITING || ticket.status() == TicketStatus.REQUEUING
                || ticket.status() == TicketStatus.REQUEUE_OFFERING
                || ticket.status() == TicketStatus.REQUEUING_UNKNOWN) {
            diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, "WAITING", "QUEUE_WAITING", 0, null);
            throw new AuthStateTooEarlyException("QUEUE_WAITING");
        }
        if (ticket.status() != TicketStatus.READY) {
            diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, "REJECTED", "TICKET_NOT_READY", 0, null);
            throw new AuthStateConflictException("TICKET_NOT_READY");
        }
        String permitKey = null;
        boolean permitWasAlreadyReserved = false;
        boolean ownershipTransferred = false;
        try {
            Optional<String> reservedPermit;
            long permitStarted = System.nanoTime();
            if (ticket.bcryptPermitKey() != null && ownsBcryptPermit(ticketId, ticket.bcryptPermitKey())) {
                reservedPermit = Optional.of(ticket.bcryptPermitKey());
                permitWasAlreadyReserved = true;
            } else {
                reservedPermit = reserveBcryptPermit(ticketId);
            }
            if (reservedPermit.isEmpty()) {
                diagnosticLoginStage("PERMIT_RESERVATION", ticketId, "DENIED", "BCRYPT_CAPACITY",
                        elapsedMillis(permitStarted), null);
                if (admissionCoordinator.localBcryptPermitKey(ticketId) != null) {
                    throw new AuthStateConflictException("QUEUE_LOGIN_IN_PROGRESS");
                }
                Ticket latest = tickets.get(ticketId);
                if (latest != null && latest.status() == TicketStatus.VERIFYING) {
                    throw new AuthStateConflictException("QUEUE_LOGIN_IN_PROGRESS");
                }
                throw new AuthStateTooManyRequestsException("BCRYPT_CAPACITY");
            }
            permitKey = reservedPermit.get();
            diagnosticLoginStage("PERMIT_RESERVATION", ticketId, "ACQUIRED", "NONE",
                    elapsedMillis(permitStarted), null);
            Ticket latest = tickets.get(ticketId);
            if (latest == null) {
                throw new AuthStateNotFoundException("TICKET_UNKNOWN");
            }
            if (latest.status() == TicketStatus.VERIFYING) {
                throw new AuthStateConflictException("QUEUE_LOGIN_IN_PROGRESS");
            }
            if (latest.status() != TicketStatus.READY) {
                diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, latest.status().name(), "TICKET_NOT_READY",
                        0, null);
                throw new AuthStateTooEarlyException("QUEUE_WAITING");
            }
            if (latest.loginOperationId() != null && !latest.loginOperationId().equals(loginOperationId)) {
                throw new AuthStateConflictException("LOGIN_OPERATION_MISMATCH");
            }
            if (latest.authIdentityHash() != null && requestedIdentityHash != null
                    && !latest.authIdentityHash().equals(requestedIdentityHash)) {
                throw new AuthStateConflictException("LOGIN_IDENTITY_MISMATCH");
            }
            Instant now = Instant.now();
            if (isTicketExpired(latest, now)) {
                expireTicket(tickets, latest);
                diagnosticLoginStage("TICKET_STATE_VALIDATE", ticketId, "EXPIRED", "TICKET_EXPIRED", 0, null);
                throw new AuthStateExpiredException();
            }
            String stableSid = latest.sid() == null ? UUID.randomUUID().toString() : latest.sid();
            Ticket claimed = latest.withLoginClaim(loginOperationId, UUID.randomUUID().toString(),
                    now.plusSeconds(properties.bcryptPermitLeaseSeconds()), permitKey, stableSid);
            if (requestedIdentityHash != null) {
                String phase = claimed.authPhase() == null ? "IDENTITY_BOUND" : claimed.authPhase();
                claimed = claimed.withAuthentication(phase, requestedIdentityHash, claimed.authenticatedUserId());
            }
            if (!tickets.replace(ticketId, latest, claimed)) {
                diagnosticLoginStage("CLAIM_CAS", ticketId, "CAS_MISS", "QUEUE_LOGIN_IN_PROGRESS", 0, null);
                throw new AuthStateConflictException("QUEUE_LOGIN_IN_PROGRESS");
            }
            diagnosticEvent("VERIFYING", ticketId, TicketStatus.VERIFYING.name());
            diagnosticLoginStage("CLAIM_CAS", ticketId, "CAS_SUCCESS", "NONE", 0, null);
            ownershipTransferred = true;
            return claimed;
        } finally {
            if (!ownershipTransferred && permitKey != null && !permitWasAlreadyReserved) {
                releaseBcryptPermit(ticketId, permitKey);
            }
        }
    }

    void reconcileFailedLogin(Ticket claim) {
        reconcileFailedLogin(claim, null);
    }

    void reconcileFailedLogin(Ticket claim, LoginOutcome observedOutcome) {
        if (claim == null) {
            return;
        }
        HazelcastInstance hz = requireHazelcast();
        IMap<String, Ticket> tickets = hz.getMap(TICKETS);
        IMap<String, AuthSession> sessions = hz.getMap(SESSIONS);
        for (int attempt = 0; attempt < 4; attempt++) {
            Ticket current = tickets.get(claim.ticketId());
            if (!ownsClaim(current, claim)) {
                diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(), "SKIPPED_OWNERSHIP_LOST", "NONE",
                        0, null);
                return;
            }
            if (ownsBcryptPermit(claim.ticketId(), current.bcryptPermitKey())) {
                diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(), "DEFERRED_ACTIVE_PERMIT", "NONE",
                        0, null);
                return;
            }
            AuthSession session = current.sid() == null ? null : sessions.get(current.sid());
            if (session != null && session.status() == SessionStatus.PROVISIONING) {
                diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(), "DEFERRED_PROVISIONING", "NONE",
                        0, null);
                return;
            }
            if (observedOutcome == LoginOutcome.INVALID_CREDENTIALS
                    && !"INVALID_CREDENTIALS".equals(current.authPhase())) {
                try {
                    markInvalidCredentials(claim);
                } catch (RuntimeException markerFailure) {
                    diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(),
                            "DEFERRED_INVALID_CREDENTIAL_MARKER", "AUTH_STATE_UNAVAILABLE", 0, markerFailure);
                    return;
                }
                current = tickets.get(claim.ticketId());
                if (!ownsClaim(current, claim)
                        || !"INVALID_CREDENTIALS".equals(current.authPhase())) {
                    diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(), "SKIPPED_OWNERSHIP_CHANGED",
                            "NONE", 0, null);
                    return;
                }
            }
            if (current.authPhase() != null && current.authPhase().equals("INVALID_CREDENTIALS")) {
                Ticket consumed = current.withState(TicketStatus.CONSUMED, LoginOutcome.INVALID_CREDENTIALS,
                        current.loginOperationId(), null, null, null, current.bcryptPermitKey(), current.sid());
                if (tickets.replace(current.ticketId(), current, consumed)) {
                    tickets.set(current.ticketId(), consumed, TERMINAL_OBSERVATION_SECONDS, TimeUnit.SECONDS);
                    releaseActiveTicket(current.ticketId());
                    diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(),
                            "CONSUMED_INVALID_CREDENTIALS", "INVALID_CREDENTIALS", 0, null);
                    return;
                }
                continue;
            }
            if (session != null && session.status() == SessionStatus.ACTIVE
                    && current.loginOperationId().equals(session.loginOperationId())
                    && java.util.Objects.equals(current.ticketId(), session.ticketId())
                    && session.userId().equals(current.authenticatedUserId())
                    && isLoginRecoveryValid(session, Instant.now())) {
                Ticket consumed = current.withState(TicketStatus.CONSUMED, LoginOutcome.SUCCESS,
                        current.loginOperationId(), null, null, null, current.bcryptPermitKey(), current.sid());
                if (tickets.replace(current.ticketId(), current, consumed)) {
                    tickets.set(current.ticketId(), consumed, TERMINAL_OBSERVATION_SECONDS, TimeUnit.SECONDS);
                    releaseActiveTicket(current.ticketId());
                    diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(), "CONSUMED_SUCCESS", "NONE",
                            0, null);
                    return;
                }
                continue;
            }
            if (session != null) {
                diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(), "DEFERRED_SESSION_PRESENT", "NONE",
                        0, null);
                return;
            }
            if (current.authIdentityHash() == null) {
                diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(), "DEFERRED_IDENTITY_UNBOUND", "NONE",
                        0, null);
                return;
            }
            Ticket retryable = current.withState(TicketStatus.RETRYABLE, null,
                    current.loginOperationId(), null, null, null, null, current.sid());
            if (tickets.replace(current.ticketId(), current, retryable)) {
                releaseBcryptPermit(current.ticketId(), current.bcryptPermitKey());
                diagnosticLoginStage("EXCEPTION_RECONCILE", current.ticketId(), "RETRYABLE", "NONE", 0, null);
                requeueReadyTicket(tickets, retryable);
                return;
            }
        }
        diagnosticLoginStage("EXCEPTION_RECONCILE", claim.ticketId(), "CAS_RETRIES_EXHAUSTED", "NONE", 0, null);
    }

    void completeTicket(HazelcastAuthStateStore.Ticket ticket, HazelcastAuthStateStore.LoginOutcome outcome,
            String sid) {
        HazelcastInstance hz = operations.requireHazelcast();
        IMap<String, HazelcastAuthStateStore.Ticket> tickets = hz.getMap(TICKETS);
        HazelcastAuthStateStore.Ticket current = tickets.get(ticket.ticketId());
        if (!operations.ownsClaim(current, ticket)) {
            throw new AuthStateUnavailableException();
        }
        HazelcastAuthStateStore.Ticket consumed = current.withState(
                HazelcastAuthStateStore.TicketStatus.CONSUMED, outcome, null, null, null, null,
                current.bcryptPermitKey(), sid);
        if (!tickets.replace(ticket.ticketId(), current, consumed)) {
            throw new AuthStateUnavailableException();
        }
        tickets.set(ticket.ticketId(), consumed, TERMINAL_OBSERVATION_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS);
        operations.diagnosticEvent("CONSUMED", ticket.ticketId(), HazelcastAuthStateStore.TicketStatus.CONSUMED.name());
        operations.releaseActiveTicket(ticket.ticketId());
    }

    String loginIdentityHash(String email) {
        if (email == null) {
            throw new AuthStateConflictException("LOGIN_IDENTITY_MISMATCH");
        }
        return operations.hmac("auth-login-identity:" + email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    HazelcastAuthStateStore.Ticket bindAuthenticationIdentity(HazelcastAuthStateStore.Ticket claim,
            String identityHash) {
        return updateClaimAuthentication(claim, current -> {
            if (current.authIdentityHash() != null && !current.authIdentityHash().equals(identityHash)) {
                throw new AuthStateConflictException("LOGIN_IDENTITY_MISMATCH");
            }
            if (current.authenticatedUserId() != null && current.authPhase() != null
                    && !"USER_BOUND".equals(current.authPhase())) {
                throw new AuthStateConflictException("LOGIN_IDENTITY_MISMATCH");
            }
            return current.withAuthentication("AUTHENTICATING", identityHash, current.authenticatedUserId());
        }, "AUTHENTICATING", identityHash, claim.authenticatedUserId());
    }

    HazelcastAuthStateStore.Ticket bindAuthenticatedUser(HazelcastAuthStateStore.Ticket claim, Long userId) {
        if (userId == null) {
            throw new AuthStateUnavailableException();
        }
        return updateClaimAuthentication(claim, current -> {
            if (current.authenticatedUserId() != null && !current.authenticatedUserId().equals(userId)) {
                throw new AuthStateConflictException("LOGIN_IDENTITY_MISMATCH");
            }
            return current.withAuthentication("USER_BOUND", current.authIdentityHash(), userId);
        }, "USER_BOUND", claim.authIdentityHash(), userId);
    }

    HazelcastAuthStateStore.Ticket markInvalidCredentials(HazelcastAuthStateStore.Ticket claim) {
        return updateClaimAuthentication(claim,
                current -> current.withAuthentication("INVALID_CREDENTIALS", current.authIdentityHash(), null),
                "INVALID_CREDENTIALS", claim.authIdentityHash(), null);
    }

    HazelcastAuthStateStore.Ticket updateClaimAuthentication(HazelcastAuthStateStore.Ticket claim,
            java.util.function.Function<HazelcastAuthStateStore.Ticket, HazelcastAuthStateStore.Ticket> update,
            String phase, String identityHash, Long userId) {
        IMap<String, HazelcastAuthStateStore.Ticket> tickets = operations.requireHazelcast().getMap(TICKETS);
        HazelcastAuthStateStore.Ticket current = tickets.get(claim.ticketId());
        if (!operations.ownsClaim(current, claim)) {
            throw new AuthStateUnavailableException();
        }
        HazelcastAuthStateStore.Ticket updated = update.apply(current);
        if (!updated.equals(current) && !tickets.replace(current.ticketId(), current, updated)) {
            current = tickets.get(claim.ticketId());
            if (!operations.ownsClaim(current, claim)) {
                throw new AuthStateUnavailableException();
            }
        } else if (!updated.equals(current)) {
            current = tickets.get(claim.ticketId());
        }
        if (!operations.ownsClaim(current, claim) || !phase.equals(current.authPhase())
                || (identityHash != null && !identityHash.equals(current.authIdentityHash()))
                || !java.util.Objects.equals(userId, current.authenticatedUserId())) {
            throw new AuthStateUnavailableException();
        }
        return current;
    }

    boolean admit(HazelcastAuthStateStore.Ticket ticket) {
        HazelcastInstance hz = operations.requireHazelcast();
        IMap<String, HazelcastAuthStateStore.Ticket> tickets = hz.getMap(TICKETS);
        Instant now = Instant.now();
        Instant readyExpiry = min(ticket.expiresAt(), now.plusSeconds(properties.readyTicketSeconds()));
        HazelcastAuthStateStore.Ticket admitting = ticket.withState(HazelcastAuthStateStore.TicketStatus.ADMITTING,
                null, null, null, null, null, null, null);
        if (!tickets.replace(ticket.ticketId(), ticket, admitting)) {
            return false;
        }
        HazelcastAuthStateStore.Ticket ready = admitting.withState(HazelcastAuthStateStore.TicketStatus.READY,
                null, null, null, null, readyExpiry, null, null);
        String reservedPermitKey = null;
        if (reservePermitOnReady.getAsBoolean()) {
            Optional<String> reserved = admissionCoordinator.reserveBcryptPermit(ticket.ticketId());
            if (reserved.isEmpty()) {
                tickets.replace(ticket.ticketId(), admitting, ticket);
                return false;
            }
            reservedPermitKey = reserved.get();
            ready = admitting.withState(HazelcastAuthStateStore.TicketStatus.READY, null, null, null, null,
                    readyExpiry, reservedPermitKey, null);
        }
        boolean updated = tickets.replace(ticket.ticketId(), admitting, ready);
        if (updated) {
            hz.<HazelcastAuthStateStore.QueueToken>getQueue(ORDER)
                    .remove(new HazelcastAuthStateStore.QueueToken(ticket.ticketId(), ticket.enqueueGeneration()));
            operations.diagnosticEvent("READY", ticket.ticketId(),
                    HazelcastAuthStateStore.TicketStatus.READY.name());
        } else if (reservedPermitKey != null) {
            admissionCoordinator.releaseBcryptPermit(ticket.ticketId(), reservedPermitKey);
        }
        return updated;
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
