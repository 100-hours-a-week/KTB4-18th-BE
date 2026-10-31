package com.muse.meomuneum.auth.application;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.muse.meomuneum.auth.exception.AuthStateBusyException;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateExpiredException;
import com.muse.meomuneum.auth.exception.AuthStateTooEarlyException;
import com.muse.meomuneum.auth.exception.AuthStateTooManyRequestsException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;

final class QueuedLoginDiagnostics {
    private final HazelcastAuthStateStore authStateStore;

    QueuedLoginDiagnostics(HazelcastAuthStateStore authStateStore) {
        this.authStateStore = authStateStore;
    }

    <T> T loginStage(HazelcastAuthStateStore.Ticket ticket, String correlationId, String stage,
            Supplier<T> action) {
        long started = System.nanoTime();
        try {
            T result = action.get();
            if (ticket != null) {
                authStateStore.diagnosticLoginStage(stage, ticket.ticketId(), "SUCCESS", "NONE",
                        elapsedMillis(started), null);
            } else {
                authStateStore.diagnosticLoginRequestStage(stage, correlationId, "SUCCESS", "NONE",
                        elapsedMillis(started), null);
            }
            return result;
        } catch (RuntimeException failure) {
            String errorCode = diagnosticErrorCode(failure);
            if (ticket != null) {
                authStateStore.diagnosticLoginStage(stage, ticket.ticketId(), "ERROR", errorCode,
                        elapsedMillis(started), failure);
            } else {
                authStateStore.diagnosticLoginRequestStage(stage, correlationId, "ERROR", errorCode,
                        elapsedMillis(started), failure);
            }
            throw failure;
        }
    }

    String diagnosticErrorCode(RuntimeException failure) {
        if (failure instanceof AuthStateConflictException conflict) {
            return conflict.code();
        }
        if (failure instanceof AuthStateTooEarlyException tooEarly) {
            return tooEarly.code();
        }
        if (failure instanceof AuthStateTooManyRequestsException tooManyRequests) {
            return tooManyRequests.code();
        }
        if (failure instanceof AuthenticationFailedException) {
            return "INVALID_CREDENTIALS";
        }
        if (failure instanceof AuthStateExpiredException) {
            return "TICKET_EXPIRED";
        }
        if (failure instanceof AuthStateUnavailableException || failure instanceof AuthStateBusyException) {
            return "AUTH_STATE_UNAVAILABLE";
        }
        return "OTHER";
    }

    long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedNanos));
    }
}
