package com.muse.meomuneum.auth.service;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hazelcast.core.HazelcastInstance;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateBusyException;
import com.muse.meomuneum.global.security.AuthSessionRevokedEvent;

@Service
public class HazelcastAuthStateStore {
    private static final String LOGIN_SESSION_INDEX = "auth-login-session-index-v2";
    private final AuthStateProperties properties;
    private final HazelcastAuthStateAccess stateAccess;
    private final AuthUserSessionIndexStore userSessionIndexStore;
    private final HazelcastAuthSessionStore sessionStore;
    private final Semaphore requestSemaphore;
    private ApplicationEventPublisher events = ignored -> {
    };

    @Autowired
    public void setRevocationEvents(ApplicationEventPublisher publisher) {
        events = publisher;
    }

    public HazelcastAuthStateStore(ObjectProvider<HazelcastInstance> hazelcastProvider,
            AuthStateProperties properties) {
        this.properties = properties;
        this.stateAccess = new HazelcastAuthStateAccess(hazelcastProvider, properties);
        this.userSessionIndexStore = new AuthUserSessionIndexStore(hazelcastProvider, properties);
        this.requestSemaphore = new Semaphore(properties.localAuthConcurrency());
        this.sessionStore = new HazelcastAuthSessionStore(hazelcastProvider, properties,
                userSessionIndexStore, stateAccess, new HazelcastAuthSessionStore.Hooks() {
                    @Override
                    public void indexLoginSession(HazelcastInstance hz, String loginOperationId, String sid,
                            Instant refreshExpiresAt) {
                        long ttl = Math.max(1, Duration.between(Instant.now(), refreshExpiresAt).toSeconds());
                        hz.<String, String>getMap(LOGIN_SESSION_INDEX).put(loginOperationId, sid, ttl,
                                TimeUnit.SECONDS);
                    }

                    @Override
                    public void promoteUserSlot(Long userId, String sid) {
                        userSessionIndexStore.promote(userId, sid);
                    }

                });
    }

    public AuthStatePermit acquireRequestPermit() {
        if (!requestSemaphore.tryAcquire()) {
            throw new AuthStateBusyException();
        }
        return new AuthStatePermit(requestSemaphore);
    }

    private void restoreSessionCount() {
        HazelcastInstance hz = stateAccess.requireHazelcast();
        sessionStore.restoreSessionCount(hz.getMap("auth-sessions-v2"));
    }

    public AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt) {
        restoreSessionCount();
        return sessionStore.createSession(userId, sid, currentJti, refreshHash, loginOperationId,
                encryptedRefreshToken, accessExpiresAt, refreshExpiresAt, null, null);
    }

    public AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt, String userAgentSummary) {
        restoreSessionCount();
        return sessionStore.createSession(userId, sid, currentJti, refreshHash, loginOperationId,
                encryptedRefreshToken, accessExpiresAt, refreshExpiresAt, userAgentSummary, null);
    }

    public AuthSession createSession(Long userId, String sid, String currentJti, String refreshHash,
            String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt, String userAgentSummary, String ownerClaimId) {
        restoreSessionCount();
        return sessionStore.createSession(userId, sid, currentJti, refreshHash, loginOperationId,
                encryptedRefreshToken, accessExpiresAt, refreshExpiresAt, userAgentSummary, ownerClaimId);
    }

    public Map<String, AuthSession> getSessions(Set<String> sids) {
        return sessionStore.getSessions(sids);
    }

    public AuthSession getSession(String sid) {
        return sessionStore.getSession(sid);
    }

    public boolean replaceSession(AuthSession expected, AuthSession updated) {
        return sessionStore.replaceSession(expected, updated);
    }

    public AuthSession revokeSession(String sid, Long expectedUserId) {
        AuthSession revoked = sessionStore.revokeSession(sid, expectedUserId);
        if (revoked != null && revoked.status() == SessionStatus.REVOKED) {
            try {
                events.publishEvent(new AuthSessionRevokedEvent(revoked.userId(), revoked.sid(), revoked.generation()));
            } catch (RuntimeException ignored) {
                return revoked;
            }
        }
        return revoked;
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

    public record AuthStatePermit(Semaphore semaphore) implements AutoCloseable {
        @Override
        public void close() {
            semaphore.release();
        }
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
