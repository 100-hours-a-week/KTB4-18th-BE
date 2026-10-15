package com.muse.meomuneum.auth.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthStateBusyException;
import com.muse.meomuneum.auth.exception.AuthStateTooManyRequestsException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;

/** Owns process-local request, bcrypt, and active-ticket permits. */
final class LoginQueueAdmissionCoordinator {
    private record RateLimitWindow(long minute, int count) {
    }

    private final AuthStateProperties properties;
    private final Semaphore localAuthSemaphore;
    private final Semaphore localBcryptSemaphore;
    private final Semaphore activeTicketSemaphore;
    private final AtomicInteger activeTicketCount = new AtomicInteger();
    private final Map<String, String> localBcryptPermitKeys = new ConcurrentHashMap<>();
    private final Object activeTicketReservationLock = new Object();
    private final Map<String, String> locallyReservedTickets = new HashMap<>();
    private final Map<String, RateLimitWindow> ticketCreateRateLimits = new ConcurrentHashMap<>();
    private final AtomicLong rateLimitCleanupMinute = new AtomicLong(-1);
    private volatile boolean reservationsRestored;

    LoginQueueAdmissionCoordinator(AuthStateProperties properties) {
        this.properties = properties;
        this.localAuthSemaphore = new Semaphore(properties.localAuthConcurrency());
        this.localBcryptSemaphore = new Semaphore(properties.localBcryptConcurrency());
        this.activeTicketSemaphore = new Semaphore(properties.maxActiveTickets());
    }

    Semaphore acquireRequestPermit() {
        if (!localAuthSemaphore.tryAcquire()) {
            throw new AuthStateBusyException();
        }
        return localAuthSemaphore;
    }

    void enforceTicketCreateRateLimit(String clientAddress, java.util.function.Function<String, String> hmac) {
        String address = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress.trim();
        long currentMinute = System.currentTimeMillis() / 60_000L;
        String key = hmac.apply("create-rate:" + address);
        java.util.concurrent.atomic.AtomicBoolean admitted = new java.util.concurrent.atomic.AtomicBoolean();
        ticketCreateRateLimits.compute(key, (ignored, current) -> {
            if (current == null || current.minute() != currentMinute) {
                admitted.set(true);
                return new RateLimitWindow(currentMinute, 1);
            }
            if (current.count() >= properties.queueCreateRequestsPerMinute()) {
                admitted.set(false);
                return current;
            }
            admitted.set(true);
            return new RateLimitWindow(currentMinute, current.count() + 1);
        });
        long priorCleanupMinute = rateLimitCleanupMinute.get();
        if (priorCleanupMinute != currentMinute
                && rateLimitCleanupMinute.compareAndSet(priorCleanupMinute, currentMinute)) {
            ticketCreateRateLimits.entrySet().removeIf(entry -> entry.getValue().minute() < currentMinute - 1);
        }
        if (!admitted.get()) {
            throw new AuthStateTooManyRequestsException("RATE_LIMITED");
        }
    }

    java.util.Optional<String> reserveBcryptPermit(String ticketId) {
        if (localBcryptPermitKeys.containsKey(ticketId) || !localBcryptSemaphore.tryAcquire()) {
            return java.util.Optional.empty();
        }
        String key = java.util.UUID.randomUUID().toString();
        String raced = localBcryptPermitKeys.putIfAbsent(ticketId, key);
        if (raced != null) {
            localBcryptSemaphore.release();
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(key);
    }

    boolean renewBcryptPermit(String owner, String key) {
        return owner != null && key != null && key.equals(localBcryptPermitKeys.get(owner));
    }

    void releaseBcryptPermit(String owner, String key) {
        if (owner != null && key != null && localBcryptPermitKeys.remove(owner, key)) {
            localBcryptSemaphore.release();
        }
    }

    boolean ownsBcryptPermit(String owner, String key) {
        return owner != null && key != null && key.equals(localBcryptPermitKeys.get(owner));
    }

    String localBcryptPermitKey(String ticketId) {
        return localBcryptPermitKeys.get(ticketId);
    }

    void clearBcryptPermits() {
        localBcryptSemaphore.drainPermits();
        localBcryptSemaphore.release(properties.localBcryptConcurrency());
        localBcryptPermitKeys.clear();
    }

    int activeTicketCount() {
        return activeTicketCount.get();
    }

    boolean reservationsRestored() {
        return reservationsRestored;
    }

    boolean reserveActiveTicket(String ticketId, String reservationId) {
        if (ticketId == null || reservationId == null) {
            return false;
        }
        synchronized (activeTicketReservationLock) {
            if (!reservationsRestored) {
                return false;
            }
            String existing = locallyReservedTickets.get(ticketId);
            if (existing != null) {
                return existing.equals(reservationId);
            }
            if (!activeTicketSemaphore.tryAcquire()) {
                return false;
            }
            locallyReservedTickets.put(ticketId, reservationId);
            activeTicketCount.set(locallyReservedTickets.size());
            return true;
        }
    }

    boolean transferActiveTicketReservation(String ticketId, String previousReservationId,
            String nextReservationId) {
        synchronized (activeTicketReservationLock) {
            String existing = locallyReservedTickets.get(ticketId);
            if (previousReservationId == null && existing != null) {
                locallyReservedTickets.put(ticketId, nextReservationId);
                return true;
            }
            if (existing == null && previousReservationId == null) {
                return reserveActiveTicket(ticketId, nextReservationId);
            }
            if (!java.util.Objects.equals(existing, previousReservationId)) {
                return false;
            }
            locallyReservedTickets.put(ticketId, nextReservationId);
            return true;
        }
    }

    void releaseActiveTicket(String ticketId) {
        synchronized (activeTicketReservationLock) {
            String reservationId = locallyReservedTickets.get(ticketId);
            if (reservationId != null) {
                releaseActiveTicket(ticketId, reservationId);
            }
        }
    }

    boolean releaseActiveTicket(String ticketId, String reservationId) {
        synchronized (activeTicketReservationLock) {
            if (!java.util.Objects.equals(locallyReservedTickets.get(ticketId), reservationId)) {
                return false;
            }
            locallyReservedTickets.remove(ticketId);
            activeTicketCount.set(locallyReservedTickets.size());
            activeTicketSemaphore.release();
            return true;
        }
    }

    void beginRestore() {
        synchronized (activeTicketReservationLock) {
            reservationsRestored = false;
        }
    }

    void restoreReservations(Map<String, String> active) {
        synchronized (activeTicketReservationLock) {
            locallyReservedTickets.clear();
            locallyReservedTickets.putAll(active);
            activeTicketSemaphore.drainPermits();
            activeTicketSemaphore.release(properties.maxActiveTickets());
            if (!activeTicketSemaphore.tryAcquire(active.size())) {
                locallyReservedTickets.clear();
                activeTicketCount.set(0);
                throw new AuthStateUnavailableException();
            }
            activeTicketCount.set(active.size());
        }
    }

    void markReservationsRestored() {
        reservationsRestored = true;
    }
}
