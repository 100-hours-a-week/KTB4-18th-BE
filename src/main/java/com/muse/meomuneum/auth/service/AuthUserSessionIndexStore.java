package com.muse.meomuneum.auth.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

final class AuthUserSessionIndexStore {
    private static final String SESSIONS = "auth-sessions-v2";
    private static final String USER_INDEX = "auth-user-session-index-v2";

    private final ObjectProvider<HazelcastInstance> hazelcastProvider;
    private final AuthStateProperties properties;

    AuthUserSessionIndexStore(ObjectProvider<HazelcastInstance> hazelcastProvider,
            AuthStateProperties properties) {
        this.hazelcastProvider = hazelcastProvider;
        this.properties = properties;
    }

    void reserve(Long userId, String sid) {
        IMap<String, HazelcastAuthStateStore.UserSessionIndex> indexes = getUserIndex();
        String key = userId.toString();
        for (int attempt = 0; attempt < 8; attempt++) {
            HazelcastAuthStateStore.UserSessionIndex current = indexes.get(key);
            Map<String, HazelcastAuthStateStore.IndexSlot> slots = cleanedSlots(userId, current);
            if (slots.containsKey(sid)) {
                return;
            }
            if (slots.size() >= properties.maxDeviceSessions()) {
                throw new AuthStateConflictException("DEVICE_SESSION_LIMIT");
            }
            Instant now = Instant.now();
            slots.put(sid, new HazelcastAuthStateStore.IndexSlot(
                    HazelcastAuthStateStore.SlotStatus.PENDING, now.plusSeconds(60)));
            HazelcastAuthStateStore.UserSessionIndex updated = new HazelcastAuthStateStore.UserSessionIndex(
                    Map.copyOf(slots));
            boolean replaced = current == null
                    ? indexes.putIfAbsent(key, updated) == null
                    : indexes.replace(key, current, updated);
            if (replaced) {
                return;
            }
        }
        throw new AuthStateUnavailableException();
    }

    void promote(Long userId, String sid) {
        update(userId, sid, new HazelcastAuthStateStore.IndexSlot(
                HazelcastAuthStateStore.SlotStatus.ACTIVE, null));
    }

    void release(Long userId, String sid) {
        IMap<String, HazelcastAuthStateStore.UserSessionIndex> indexes = getUserIndex();
        String key = userId.toString();
        for (int attempt = 0; attempt < 8; attempt++) {
            HazelcastAuthStateStore.UserSessionIndex current = indexes.get(key);
            if (current == null || !current.sessions().containsKey(sid)) {
                return;
            }
            Map<String, HazelcastAuthStateStore.IndexSlot> slots = new HashMap<>(current.sessions());
            slots.remove(sid);
            HazelcastAuthStateStore.UserSessionIndex updated = new HazelcastAuthStateStore.UserSessionIndex(
                    Map.copyOf(slots));
            boolean changed = slots.isEmpty()
                    ? indexes.remove(key, current)
                    : indexes.replace(key, current, updated);
            if (changed) {
                return;
            }
        }
        throw new AuthStateUnavailableException();
    }

    boolean isActiveIndexedSession(HazelcastAuthStateStore.AuthSession session, Long userId) {
        Instant now = Instant.now();
        return session != null && userId.equals(session.userId())
                && session.status() == HazelcastAuthStateStore.SessionStatus.ACTIVE
                && session.refreshIdleExpiresAt().isAfter(now)
                && session.refreshAbsoluteExpiresAt().isAfter(now);
    }

    void prune(Long userId) {
        IMap<String, HazelcastAuthStateStore.UserSessionIndex> indexes = getUserIndex();
        String key = userId.toString();
        for (int attempt = 0; attempt < 8; attempt++) {
            HazelcastAuthStateStore.UserSessionIndex current = indexes.get(key);
            if (current == null) {
                return;
            }
            Map<String, HazelcastAuthStateStore.IndexSlot> slots = cleanedSlots(userId, current);
            if (slots.size() == current.sessions().size() && !slots.isEmpty()) {
                return;
            }
            boolean changed = slots.isEmpty()
                    ? indexes.remove(key, current)
                    : indexes.replace(key, current, new HazelcastAuthStateStore.UserSessionIndex(Map.copyOf(slots)));
            if (changed) {
                return;
            }
        }
        throw new AuthStateUnavailableException();
    }

    void reconcilePendingSlots(
            List<Map.Entry<String, HazelcastAuthStateStore.UserSessionIndex>> entries) {
        IMap<String, HazelcastAuthStateStore.AuthSession> sessions = getSessions();
        for (Map.Entry<String, HazelcastAuthStateStore.UserSessionIndex> entry : entries) {
            Long userId = Long.valueOf(entry.getKey());
            for (Map.Entry<String, HazelcastAuthStateStore.IndexSlot> slot : entry.getValue().sessions().entrySet()) {
                if (slot.getValue().status() != HazelcastAuthStateStore.SlotStatus.PENDING) {
                    continue;
                }
                HazelcastAuthStateStore.AuthSession candidate = sessions.get(slot.getKey());
                if (isActiveIndexedSession(candidate, userId)) {
                    promote(userId, slot.getKey());
                }
            }
            prune(userId);
        }
    }

    List<HazelcastAuthStateStore.AuthSession> activeSessions(Long userId, int maxDeviceSessions) {
        IMap<String, HazelcastAuthStateStore.AuthSession> sessions = getSessions();
        IMap<String, HazelcastAuthStateStore.UserSessionIndex> indexes = getUserIndex();
        HazelcastAuthStateStore.UserSessionIndex beforePrune = indexes.get(userId.toString());
        if (beforePrune != null) {
            prune(userId);
        }
        HazelcastAuthStateStore.UserSessionIndex index = indexes.get(userId.toString());
        if (index == null) {
            return List.of();
        }
        Instant now = Instant.now();
        List<HazelcastAuthStateStore.AuthSession> active = index.sessions().entrySet().stream()
                .filter(entry -> entry.getValue().status() == HazelcastAuthStateStore.SlotStatus.ACTIVE)
                .map(entry -> sessions.get(entry.getKey()))
                .filter(session -> session != null && userId.equals(session.userId())
                        && session.status() == HazelcastAuthStateStore.SessionStatus.ACTIVE
                        && session.refreshIdleExpiresAt().isAfter(now)
                        && session.refreshAbsoluteExpiresAt().isAfter(now))
                .sorted(Comparator.comparing(HazelcastAuthStateStore.AuthSession::createdAt).reversed())
                .toList();
        if (active.size() > maxDeviceSessions) {
            throw new AuthStateUnavailableException();
        }
        return active;
    }

    private void update(Long userId, String sid, HazelcastAuthStateStore.IndexSlot slot) {
        IMap<String, HazelcastAuthStateStore.UserSessionIndex> indexes = getUserIndex();
        String key = userId.toString();
        indexes.lock(key);
        try {
            HazelcastAuthStateStore.UserSessionIndex current = indexes.get(key);
            Map<String, HazelcastAuthStateStore.IndexSlot> slots = new HashMap<>(
                    current == null ? Map.of() : current.sessions());
            slots.put(sid, slot);
            indexes.put(key, new HazelcastAuthStateStore.UserSessionIndex(Map.copyOf(slots)));
        } finally {
            indexes.unlock(key);
        }
    }

    private Map<String, HazelcastAuthStateStore.IndexSlot> cleanedSlots(
            Long userId, HazelcastAuthStateStore.UserSessionIndex current) {
        Map<String, HazelcastAuthStateStore.IndexSlot> slots = current == null
                ? new HashMap<>()
                : new HashMap<>(current.sessions());
        Instant now = Instant.now();
        IMap<String, HazelcastAuthStateStore.AuthSession> sessions = getSessions();
        var iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, HazelcastAuthStateStore.IndexSlot> entry = iterator.next();
            HazelcastAuthStateStore.IndexSlot slot = entry.getValue();
            if (slot.status() == HazelcastAuthStateStore.SlotStatus.PENDING) {
                HazelcastAuthStateStore.AuthSession pendingSession = sessions.get(entry.getKey());
                if (isActiveIndexedSession(pendingSession, userId)) {
                    entry.setValue(new HazelcastAuthStateStore.IndexSlot(
                            HazelcastAuthStateStore.SlotStatus.ACTIVE, null));
                } else if (slot.leaseUntil() == null || !slot.leaseUntil().isAfter(now)) {
                    iterator.remove();
                }
                continue;
            }
            HazelcastAuthStateStore.AuthSession session = sessions.get(entry.getKey());
            if (session == null || !userId.equals(session.userId())
                    || session.status() != HazelcastAuthStateStore.SessionStatus.ACTIVE
                    || !session.refreshIdleExpiresAt().isAfter(now)
                    || !session.refreshAbsoluteExpiresAt().isAfter(now)) {
                iterator.remove();
            }
        }
        return slots;
    }

    private IMap<String, HazelcastAuthStateStore.UserSessionIndex> getUserIndex() {
        return requireHazelcast().getMap(USER_INDEX);
    }

    private IMap<String, HazelcastAuthStateStore.AuthSession> getSessions() {
        return requireHazelcast().getMap(SESSIONS);
    }

    private HazelcastInstance requireHazelcast() {
        HazelcastInstance instance = hazelcastProvider.getIfAvailable();
        if (instance == null || !instance.getLifecycleService().isRunning()) {
            throw new AuthStateUnavailableException();
        }
        try {
            int members = instance.getCluster().getMembers().size();
            if (members < properties.minimumHazelcastMembers()
                    || members > properties.expectedHazelcastMembers()) {
                throw new AuthStateUnavailableException();
            }
        } catch (RuntimeException exception) {
            if (exception instanceof AuthStateUnavailableException) {
                throw exception;
            }
            throw new AuthStateUnavailableException();
        }
        return instance;
    }
}
