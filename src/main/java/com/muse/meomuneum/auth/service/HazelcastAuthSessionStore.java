package com.muse.meomuneum.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.ObjectProvider;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

final class HazelcastAuthSessionStore {
    private static final String SESSIONS = "auth-sessions-v2";
    private static final String LOGIN_SESSION_INDEX = "auth-login-session-index-v2";
    private static final long LOGIN_RECOVERY_WINDOW_SECONDS = 900;

    interface Hooks {
        void indexLoginSession(HazelcastInstance hz, String loginOperationId, String sid,
                Instant refreshExpiresAt);

        void promoteUserSlot(Long userId, String sid);

    }

    private final AuthStateProperties properties;
    private final AuthUserSessionIndexStore userSessionIndexStore;
    private final HazelcastAuthStateAccess stateAccess;
    private final Hooks hooks;
    private final Function<String, String> hash;
    private final AuthSessionCapacity sessionCapacity;

    HazelcastAuthSessionStore(ObjectProvider<HazelcastInstance> hazelcastProvider,
            AuthStateProperties properties, AuthUserSessionIndexStore userSessionIndexStore,
            HazelcastAuthStateAccess stateAccess, Hooks hooks) {
        this.properties = properties;
        this.userSessionIndexStore = userSessionIndexStore;
        this.stateAccess = stateAccess;
        this.hooks = hooks;
        this.hash = this::hmac;
        this.sessionCapacity = new AuthSessionCapacity(properties);
    }

    HazelcastAuthStateStore.AuthSession createSession(Long userId, String sid, String currentJti,
            String refreshHash, String loginOperationId, String encryptedRefreshToken, Instant accessExpiresAt,
            Instant refreshExpiresAt, String userAgentSummary, String ownerClaimId) {
        if (!sessionCapacity.restored()) {
            throw new AuthStateUnavailableException();
        }
        HazelcastInstance hz = stateAccess.requireHazelcast();
        IMap<String, HazelcastAuthStateStore.AuthSession> sessions = hz.getMap(SESSIONS);
        HazelcastAuthStateStore.AuthSession existing = sessions.get(sid);
        if (existing != null) {
            if (isCanonicalLoginSession(existing, userId, loginOperationId, ownerClaimId)) {
                return existing;
            }
            throw new AuthStateConflictException("LOGIN_SESSION_CONFLICT");
        }
        sessionCapacity.assertAvailable();
        Instant now = Instant.now();
        Instant idleExpiry = min(now.plusSeconds(properties.sessionIdleSeconds()), refreshExpiresAt);
        String currentJtiHash = jtiHash(currentJti);
        HazelcastAuthStateStore.AuthSession provisioning = new HazelcastAuthStateStore.AuthSession(userId, sid,
                userAgentSummary, HazelcastAuthStateStore.SessionStatus.PROVISIONING, currentJtiHash, refreshHash,
                null, null, null, null, null, loginOperationId,
                now.plusSeconds(LOGIN_RECOVERY_WINDOW_SECONDS), 1, refreshHash, encryptedRefreshToken, 1, now, now,
                accessExpiresAt, idleExpiry, refreshExpiresAt, null, ownerClaimId);
        userSessionIndexStore.reserve(userId, sid);
        boolean capacityReserved = false;
        try {
            HazelcastAuthStateStore.AuthSession raced = sessions.putIfAbsent(sid, provisioning,
                    ttlSeconds(idleExpiry), TimeUnit.SECONDS);
            if (raced != null) {
                HazelcastAuthStateStore.AuthSession latest = sessions.get(sid);
                if (latest != null && isCanonicalLoginSession(latest, userId, loginOperationId, ownerClaimId)) {
                    return latest;
                }
                throw new AuthStateConflictException("LOGIN_SESSION_CONFLICT");
            }
            sessionCapacity.reserve(sid);
            capacityReserved = true;
            HazelcastAuthStateStore.AuthSession active = new HazelcastAuthStateStore.AuthSession(userId, sid,
                    userAgentSummary, HazelcastAuthStateStore.SessionStatus.ACTIVE, currentJtiHash, refreshHash,
                    null, null, null, null, null, loginOperationId,
                    now.plusSeconds(LOGIN_RECOVERY_WINDOW_SECONDS), 1, refreshHash, encryptedRefreshToken, 1, now,
                    now, accessExpiresAt, idleExpiry, refreshExpiresAt, null, ownerClaimId);
            if (!sessions.replace(sid, provisioning, active)) {
                throw new AuthStateUnavailableException();
            }
            hooks.promoteUserSlot(userId, sid);
            if (loginOperationId != null) {
                hooks.indexLoginSession(hz, loginOperationId, sid, refreshExpiresAt);
            }
            return active;
        } catch (RuntimeException exception) {
            HazelcastAuthStateStore.AuthSession current = sessions.get(sid);
            if (current == null || current.status() == HazelcastAuthStateStore.SessionStatus.PROVISIONING) {
                if (current != null && sessions.remove(sid, current) && capacityReserved) {
                    releaseSessionCapacity(sid);
                }
                releaseUserSlot(userId, sid);
            }
            if (!(exception instanceof AuthStateUnavailableException)) {
                throw stateAccess.unavailable(exception);
            }
            throw exception;
        }
    }

    private boolean isCanonicalLoginSession(HazelcastAuthStateStore.AuthSession session, Long userId,
            String operationId, String ownerClaimId) {
        return session.status() == HazelcastAuthStateStore.SessionStatus.ACTIVE
                && java.util.Objects.equals(session.userId(), userId)
                && java.util.Objects.equals(session.loginOperationId(), operationId)
                && java.util.Objects.equals(session.ownerClaimId(), ownerClaimId)
                && session.loginRecoveryUntil() != null && session.loginRecoveryUntil().isAfter(Instant.now())
                && session.currentRefreshHash().equals(session.loginRecoveryRefreshHash())
                && session.encryptedLoginRecoveryRefreshToken() != null;
    }

    boolean isLoginRecoveryValid(HazelcastAuthStateStore.AuthSession session, Instant now) {
        return session.loginOperationId() != null
                && session.loginRecoveryUntil() != null && session.loginRecoveryUntil().isAfter(now)
                && session.refreshIdleExpiresAt().isAfter(now)
                && session.refreshAbsoluteExpiresAt().isAfter(now)
                && session.generation() == session.loginRecoveryGeneration()
                && session.currentRefreshHash().equals(session.loginRecoveryRefreshHash())
                && session.encryptedLoginRecoveryRefreshToken() != null;
    }

    HazelcastAuthStateStore.AuthSession getSession(String sid) {
        try {
            return stateAccess.requireHazelcast().<String, HazelcastAuthStateStore.AuthSession>getMap(SESSIONS)
                    .get(sid);
        } catch (RuntimeException exception) {
            throw stateAccess.unavailable(exception);
        }
    }

    Map<String, HazelcastAuthStateStore.AuthSession> getSessions(Set<String> sids) {
        if (sids.isEmpty() || sids.size() > 100
                || sids.stream().anyMatch(sid -> !com.muse.meomuneum.global.security.AuthSessionIds.isValid(sid))) {
            throw new IllegalArgumentException("invalid session read batch");
        }
        try {
            return Map.copyOf(stateAccess
                    .requireHazelcast().<String, HazelcastAuthStateStore.AuthSession>getMap(SESSIONS).getAll(sids));
        } catch (RuntimeException exception) {
            throw stateAccess.unavailable(exception);
        }
    }

    boolean replaceSession(HazelcastAuthStateStore.AuthSession expected,
            HazelcastAuthStateStore.AuthSession updated) {
        try {
            IMap<String, HazelcastAuthStateStore.AuthSession> sessions = stateAccess.requireHazelcast()
                    .getMap(SESSIONS);
            if (!sessions.replace(expected.sid(), expected, updated)) {
                return false;
            }
            Instant expiresAt = updated.status() == HazelcastAuthStateStore.SessionStatus.REVOKED
                    ? Instant.now().plusSeconds(properties.revokedSessionRetentionSeconds())
                    : min(updated.refreshIdleExpiresAt(), updated.refreshAbsoluteExpiresAt());
            if (!sessions.setTtl(updated.sid(), ttlSeconds(expiresAt), TimeUnit.SECONDS)) {
                throw new AuthStateUnavailableException();
            }
            return true;
        } catch (RuntimeException exception) {
            throw stateAccess.unavailable(exception);
        }
    }

    HazelcastAuthStateStore.AuthSession revokeSession(String sid, Long expectedUserId) {
        HazelcastInstance hz = stateAccess.requireHazelcast();
        IMap<String, HazelcastAuthStateStore.AuthSession> sessions = hz.getMap(SESSIONS);
        HazelcastAuthStateStore.AuthSession session = sessions.get(sid);
        if (session == null) {
            if (expectedUserId != null) {
                releaseUserSlot(expectedUserId, sid);
            }
            return null;
        }
        if (expectedUserId != null && !expectedUserId.equals(session.userId())) {
            return null;
        }
        if (session.status() == HazelcastAuthStateStore.SessionStatus.REVOKED) {
            releaseUserSlot(session.userId(), sid);
            return session;
        }
        HazelcastAuthStateStore.AuthSession revoked = session.revoked();
        if (!sessions.replace(sid, session, revoked)) {
            throw new AuthStateUnavailableException();
        }
        if (!sessions.setTtl(sid, properties.revokedSessionRetentionSeconds(), TimeUnit.SECONDS)) {
            throw new AuthStateUnavailableException();
        }
        releaseUserSlot(session.userId(), sid);
        return revoked;
    }

    void restoreSessionCount(IMap<String, HazelcastAuthStateStore.AuthSession> sessions) {
        sessionCapacity.restore(sessions);
    }

    long revokedSessionRetentionSeconds() {
        return properties.revokedSessionRetentionSeconds();
    }

    void releaseSessionCapacity(String sid) {
        sessionCapacity.release(sid);
    }

    List<HazelcastAuthStateStore.AuthSession> activeSessions(Long userId) {
        try {
            return userSessionIndexStore.activeSessions(userId, properties.maxDeviceSessions());
        } catch (RuntimeException exception) {
            throw stateAccess.unavailable(exception);
        }
    }

    String tokenHash(String token) {
        return hash.apply("refresh:" + token);
    }

    String jtiHash(String jti) {
        return hash.apply("jti:" + jti);
    }

    Instant newAbsoluteSessionExpiry() {
        return Instant.now().plusSeconds(properties.sessionAbsoluteSeconds());
    }

    Instant nextRefreshIdleExpiry(Instant absoluteExpiry) {
        Instant idle = Instant.now().plusSeconds(properties.sessionIdleSeconds());
        return idle.isBefore(absoluteExpiry) ? idle : absoluteExpiry;
    }

    private void releaseUserSlot(Long userId, String sid) {
        userSessionIndexStore.release(userId, sid);
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private long ttlSeconds(Instant expiry) {
        return Math.max(1, Duration.between(Instant.now(), expiry).toSeconds());
    }

    private String hmac(String value) {
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
}
