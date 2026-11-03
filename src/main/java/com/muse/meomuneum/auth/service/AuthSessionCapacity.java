package com.muse.meomuneum.auth.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryExpiredListener;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

final class AuthSessionCapacity {
    private static final int RECOVERY_BATCH_SIZE = 100;

    private final AuthStateProperties properties;
    private final AtomicInteger activeCount = new AtomicInteger();
    private final Set<String> countedSessionIds = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private volatile boolean restored;
    private boolean expiryListenerRegistered;

    AuthSessionCapacity(AuthStateProperties properties) {
        this.properties = properties;
    }

    boolean restored() {
        return restored;
    }

    void assertAvailable() {
        synchronized (lock) {
            if (activeCount.get() >= properties.globalSessionCapacity()) {
                throw new AuthStateUnavailableException();
            }
        }
    }

    void reserve(String sid) {
        synchronized (lock) {
            if (activeCount.incrementAndGet() > properties.globalSessionCapacity()) {
                activeCount.decrementAndGet();
                throw new AuthStateUnavailableException();
            }
            countedSessionIds.add(sid);
        }
    }

    void restore(IMap<String, HazelcastAuthStateStore.AuthSession> sessions) {
        synchronized (lock) {
            if (restored) {
                return;
            }
            if (!expiryListenerRegistered) {
                sessions.addEntryListener(
                        (EntryExpiredListener<String, HazelcastAuthStateStore.AuthSession>) event -> {
                            synchronized (lock) {
                                if (countedSessionIds.remove(event.getKey())) {
                                    activeCount.decrementAndGet();
                                }
                            }
                        }, true);
                expiryListenerRegistered = true;
            }
            int count = 0;
            var iterator = sessions.iterator(RECOVERY_BATCH_SIZE);
            while (iterator.hasNext()) {
                Map.Entry<String, HazelcastAuthStateStore.AuthSession> entry = iterator.next();
                count++;
                countedSessionIds.add(entry.getKey());
                if (count > properties.globalSessionCapacity()) {
                    throw new AuthStateUnavailableException();
                }
            }
            activeCount.set(count);
            restored = true;
        }
    }

    void release(String sid) {
        synchronized (lock) {
            if (restored && countedSessionIds.remove(sid)) {
                int remaining = activeCount.decrementAndGet();
                if (remaining < 0) {
                    activeCount.incrementAndGet();
                    countedSessionIds.add(sid);
                    throw new AuthStateUnavailableException();
                }
            }
        }
    }
}
