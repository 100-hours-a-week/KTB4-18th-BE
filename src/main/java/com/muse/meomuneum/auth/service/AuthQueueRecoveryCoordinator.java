package com.muse.meomuneum.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

/** Coordinates recovery work that must cross ticket, session, and user-session index maps. */
final class AuthQueueRecoveryCoordinator {
    private static final long TERMINAL_OBSERVATION_SECONDS = 900;

    record StartupReservationSnapshot(Map<String, String> activeReservations, long maxOrder, long operationCount) {
    }

    interface StartupRecoveryAccess {
        void restoreSessionCount();

        StartupReservationSnapshot scanActiveReservations();

        void setRestoredCounters(long operationCount, long maxOrder);
    }
    record ScheduledRecoveryState(HazelcastInstance hazelcast, IMap<String, String> loginSessionIndex,
            IMap<String, HazelcastAuthStateStore.Ticket> tickets,
            IMap<String, HazelcastAuthStateStore.AuthSession> sessions,
            List<Map.Entry<String, HazelcastAuthStateStore.UserSessionIndex>> pendingIndexes,
            List<HazelcastAuthStateStore.AuthSession> sessionBatch,
            List<HazelcastAuthStateStore.Ticket> expiredClaims,
            HazelcastAuthStateStore.ReconciliationAccumulator observations,
            ToLongFunction<Instant> ttlSeconds) {
    }

    @FunctionalInterface
    interface ScheduledQueueReconciler {
        ScheduledRecoveryState reconcileQueueState();
    }

    @FunctionalInterface
    interface LoginSessionIndexer {
        void index(HazelcastInstance hz, String loginOperationId, String sid, Instant refreshExpiresAt);
    }

    private final HazelcastAuthStateAccess stateAccess;
    private final HazelcastAuthSessionStore sessionStore;
    private final AuthUserSessionIndexStore userSessionIndexStore;
    private final LoginQueueAdmissionCoordinator admissionCoordinator;
    private final LoginSessionIndexer loginSessionIndexer;

    AuthQueueRecoveryCoordinator(HazelcastAuthStateAccess stateAccess, HazelcastAuthSessionStore sessionStore,
            AuthUserSessionIndexStore userSessionIndexStore, LoginQueueAdmissionCoordinator admissionCoordinator,
            LoginSessionIndexer loginSessionIndexer) {
        this.stateAccess = stateAccess;
        this.sessionStore = sessionStore;
        this.userSessionIndexStore = userSessionIndexStore;
        this.admissionCoordinator = admissionCoordinator;
        this.loginSessionIndexer = loginSessionIndexer;
    }

    void restoreStartupState(StartupRecoveryAccess access) {
        admissionCoordinator.beginRestore();
        access.restoreSessionCount();
        StartupReservationSnapshot snapshot = access.scanActiveReservations();
        admissionCoordinator.restoreReservations(snapshot.activeReservations());
        access.setRestoredCounters(snapshot.operationCount(), snapshot.maxOrder());
        admissionCoordinator.clearBcryptPermits();
        admissionCoordinator.markReservationsRestored();
    }

    void reconcileScheduledState(ScheduledQueueReconciler queueReconciler) {
        ScheduledRecoveryState state = queueReconciler.reconcileQueueState();
        reconcileCrossStoreState(state.hazelcast(), state.loginSessionIndex(), state.tickets(), state.sessions(),
                state.pendingIndexes(), state.sessionBatch(), state.expiredClaims(), state.observations(),
                state.ttlSeconds());
    }

    void reconcilePendingUserSlots(List<Map.Entry<String, HazelcastAuthStateStore.UserSessionIndex>> pendingIndexes) {
        userSessionIndexStore.reconcilePendingSlots(pendingIndexes);
    }

    void reconcileCrossStoreState(HazelcastInstance hz, IMap<String, String> loginSessionIndex,
            IMap<String, HazelcastAuthStateStore.Ticket> tickets,
            IMap<String, HazelcastAuthStateStore.AuthSession> sessions,
            List<Map.Entry<String, HazelcastAuthStateStore.UserSessionIndex>> pendingIndexes,
            List<HazelcastAuthStateStore.AuthSession> sessionBatch,
            List<HazelcastAuthStateStore.Ticket> expiredClaims,
            HazelcastAuthStateStore.ReconciliationAccumulator observations,
            java.util.function.ToLongFunction<Instant> ttlSeconds) {
        reconcilePendingUserSlots(pendingIndexes);
        for (HazelcastAuthStateStore.AuthSession session : sessionBatch) {
            HazelcastAuthStateStore.AuthSession cleaned = session.withoutExpiredReplayMaterial(Instant.now());
            if (cleaned != session && sessions.replace(session.sid(), session, cleaned)) {
                session = cleaned;
            }
            if (session.status() == HazelcastAuthStateStore.SessionStatus.ACTIVE
                    && sessionStore.isLoginRecoveryValid(session, Instant.now())) {
                String indexedSid = loginSessionIndex.putIfAbsent(session.loginOperationId(), session.sid(),
                        ttlSeconds.applyAsLong(session.refreshAbsoluteExpiresAt()), TimeUnit.SECONDS);
                if (indexedSid != null && !session.sid().equals(indexedSid)) {
                    throw new AuthStateUnavailableException();
                }
            }
            if (session.status() == HazelcastAuthStateStore.SessionStatus.PROVISIONING
                    && session.createdAt().plusSeconds(60).isBefore(Instant.now())
                    && sessions.remove(session.sid(), session)) {
                sessionStore.releaseSessionCapacity(session.sid());
                userSessionIndexStore.release(session.userId(), session.sid());
            } else if (session.status() == HazelcastAuthStateStore.SessionStatus.ACTIVE
                    && (!session.refreshIdleExpiresAt().isAfter(Instant.now())
                            || !session.refreshAbsoluteExpiresAt().isAfter(Instant.now()))) {
                HazelcastAuthStateStore.AuthSession revoked = session.revoked();
                if (sessions.replace(session.sid(), session, revoked)) {
                    sessions.setTtl(session.sid(), sessionStore.revokedSessionRetentionSeconds(),
                            TimeUnit.SECONDS);
                    userSessionIndexStore.release(session.userId(), session.sid());
                }
            }
        }
        for (HazelcastAuthStateStore.Ticket observed : expiredClaims) {
            HazelcastAuthStateStore.Ticket latest = tickets.get(observed.ticketId());
            if (latest != null && latest.status() == HazelcastAuthStateStore.TicketStatus.VERIFYING
                    && latest.claimLeaseUntil() != null && !latest.claimLeaseUntil().isAfter(Instant.now())
                    && java.util.Objects.equals(latest.claimId(), observed.claimId())
                    && recoverExpiredClaim(tickets, latest, hz.getMap("auth-sessions-v2"))) {
                observations.expiredClaimRecovered();
            }
        }
    }

    boolean recoverExpiredClaim(IMap<String, HazelcastAuthStateStore.Ticket> tickets,
            HazelcastAuthStateStore.Ticket observed, IMap<String, HazelcastAuthStateStore.AuthSession> sessions) {
        if (admissionCoordinator.ownsBcryptPermit(observed.ticketId(), observed.bcryptPermitKey())) {
            return false;
        }
        if (observed.loginOperationId() == null) {
            return false;
        }
        HazelcastAuthStateStore.AuthSession candidate = observed.sid() == null ? null : sessions.get(observed.sid());
        if (candidate != null && candidate.status() == HazelcastAuthStateStore.SessionStatus.PROVISIONING
                && observed.ticketId().equals(candidate.ticketId())
                && observed.loginOperationId().equals(candidate.loginOperationId())
                && java.util.Objects.equals(observed.claimId(), candidate.ownerClaimId())
                && candidate.provisioningLeaseUntil() != null
                && !candidate.provisioningLeaseUntil().isAfter(Instant.now())
                && sessions.remove(observed.sid(), candidate)) {
            sessionStore.releaseSessionCapacity(observed.sid());
            userSessionIndexStore.release(candidate.userId(), observed.sid());
            candidate = null;
        }
        HazelcastAuthStateStore.AuthSession matching = candidate != null
                && observed.loginOperationId().equals(candidate.loginOperationId())
                && java.util.Objects.equals(observed.ticketId(), candidate.ticketId())
                && (observed.authenticatedUserId() == null
                        || observed.authenticatedUserId().equals(candidate.userId()))
                && candidate.status() == HazelcastAuthStateStore.SessionStatus.ACTIVE
                && sessionStore.isLoginRecoveryValid(candidate, Instant.now())
                        ? candidate
                        : null;
        if (candidate != null && candidate.status() == HazelcastAuthStateStore.SessionStatus.PROVISIONING) {
            return false;
        }
        HazelcastAuthStateStore.LoginOutcome outcome = matching != null
                ? HazelcastAuthStateStore.LoginOutcome.SUCCESS
                : "INVALID_CREDENTIALS".equals(observed.authPhase())
                        ? HazelcastAuthStateStore.LoginOutcome.INVALID_CREDENTIALS
                        : null;
        HazelcastAuthStateStore.TicketStatus recoveredStatus = outcome == null
                ? HazelcastAuthStateStore.TicketStatus.RETRYABLE
                : HazelcastAuthStateStore.TicketStatus.CONSUMED;
        HazelcastAuthStateStore.Ticket recovered = observed.withState(recoveredStatus, outcome,
                observed.loginOperationId(), null, null, null, null, matching == null ? null : matching.sid());
        if (tickets.replace(observed.ticketId(), observed, recovered)) {
            if (recoveredStatus == HazelcastAuthStateStore.TicketStatus.CONSUMED) {
                tickets.set(observed.ticketId(), recovered, TERMINAL_OBSERVATION_SECONDS, TimeUnit.SECONDS);
                admissionCoordinator.releaseActiveTicket(observed.ticketId());
                if (matching != null) {
                    loginSessionIndexer.index(stateAccess.requireHazelcast(), observed.loginOperationId(),
                            matching.sid(), matching.refreshAbsoluteExpiresAt());
                }
            }
            admissionCoordinator.releaseBcryptPermit(observed.ticketId(), observed.bcryptPermitKey());
            return true;
        }
        return false;
    }
}
