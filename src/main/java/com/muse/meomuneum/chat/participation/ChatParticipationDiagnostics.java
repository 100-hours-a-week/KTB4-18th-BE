package com.muse.meomuneum.chat.participation;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded first-failure evidence: never pass a Throwable or its message to the logger. */
final class ChatParticipationDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(ChatParticipationDiagnostics.class);
    private final String environment;
    private final String task;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean transactionFailed = new AtomicBoolean();

    ChatParticipationDiagnostics(String environment, String task) {
        this.environment = environment;
        this.task = task;
    }

    void closed(String stage, Throwable failure, long started) {
        if (closed.compareAndSet(false, true)) {
            record("chat_participation_first_closed", stage, failure, started);
        }
    }

    void transactionFailed(String stage, Throwable failure, long started) {
        if (transactionFailed.compareAndSet(false, true)) {
            record("chat_participation_first_transaction_failure", stage, failure, started);
        }
    }

    private void record(String event, String stage, Throwable failure, long started) {
        try {
            var types = new ArrayList<String>();
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            String sqlState = "none";
            int sqlCode = 0;
            String site = "none";
            for (Throwable current = failure; current != null && visited.size() < 8
                    && visited.add(current); current = current.getCause()) {
                types.add(symbol(current.getClass().getName()));
                if (current instanceof SQLException sql && "none".equals(sqlState)) {
                    sqlState = sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")
                            ? sql.getSQLState()
                            : "none";
                    sqlCode = sql.getErrorCode();
                }
                for (StackTraceElement frame : current.getStackTrace()) {
                    if (frame.getClassName().startsWith("com.muse.")
                            && !"unknown".equals(symbol(frame.getClassName()))
                            && !"unknown".equals(symbol(frame.getMethodName()))) {
                        site = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                        break;
                    }
                }
            }
            if ("none".equals(site)) {
                site = StackWalker.getInstance().walk(frames -> frames
                        .filter(frame -> frame.getClassName().startsWith("com.muse.")
                                && !frame.getClassName().equals(ChatParticipationDiagnostics.class.getName())
                                && !frame.getMethodName().equals("failClosed"))
                        .findFirst().map(frame -> frame.getClassName() + "." + frame.getMethodName()
                                + ":" + frame.getLineNumber())
                        .orElse("none"));
            }
            long elapsed = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            log.warn(
                    "event={} environment={} task={} stage={} elapsed_ms={} causes={} sql_state={} sql_code={} site={}",
                    event, environment, task, symbol(stage), elapsed,
                    types.isEmpty() ? "none" : String.join(",", types), sqlState, sqlCode, site);
        } catch (RuntimeException ignored) {
            // A diagnostic failure must not prevent the existing permission/transport shutdown.
        }
    }

    private static String symbol(String value) {
        return value != null && value.length() <= 180 && value.matches("[A-Za-z0-9_.$<>]+") ? value : "unknown";
    }
}
