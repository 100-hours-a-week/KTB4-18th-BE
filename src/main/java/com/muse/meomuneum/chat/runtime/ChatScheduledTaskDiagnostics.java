package com.muse.meomuneum.chat.runtime;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

/** Diagnostic observer only: does not choose, replace or configure any scheduler. */
@Component
@ConditionalOnProperty(name = "chat.scheduler-diagnostics.enabled", havingValue = "true")
public final class ChatScheduledTaskDiagnostics implements ObservationHandler<ScheduledTaskObservationContext> {
    private static final Logger log = LoggerFactory.getLogger(ChatScheduledTaskDiagnostics.class);
    private static final Object KEY = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final LongSupplier clock;
    private final long limit;

    public ChatScheduledTaskDiagnostics() {
        this(System::nanoTime, 8192);
    }

    ChatScheduledTaskDiagnostics(LongSupplier clock, long limit) {
        this.clock = clock;
        this.limit = limit;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext scheduled
                && scheduled.getTargetClass().getName().startsWith("com.muse.meomuneum.chat.");
    }

    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        try {
            long id = sequence.incrementAndGet();
            if (id > limit) {
                if (id == limit + 1) {
                    log.warn("event=chat_scheduled_trace_limit limit={}", limit);
                }
                return;
            }
            Thread thread = Thread.currentThread();
            String name = thread.getName();
            String safeName = name.matches("[A-Za-z0-9_.-]{1,80}") ? name : "unknown";
            var invocation = new Invocation(id, clock.getAsLong(), thread.threadId(), safeName,
                    context.getTargetClass().getSimpleName() + "." + context.getMethod().getName());
            context.put(KEY, invocation);
            log.info("event=chat_scheduled_start invocation={} task={} thread_id={} thread={} start_ns={}",
                    invocation.id(), invocation.task(), invocation.threadId(), invocation.thread(), invocation.start());
        } catch (RuntimeException ignored) {
            // A diagnostic must not suppress scheduled work.
        }
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        try {
            Invocation invocation = context.get(KEY);
            if (invocation == null) {
                return;
            }
            long end = clock.getAsLong();
            long duration = Math.max(0, end - invocation.start());
            log.info("event=chat_scheduled_end invocation={} task={} thread_id={} thread={} start_ns={} end_ns={} "
                    + "duration_ns={} duration_ms={} complete={}", invocation.id(), invocation.task(),
                    invocation.threadId(), invocation.thread(), invocation.start(), end, duration,
                    TimeUnit.NANOSECONDS.toMillis(duration), context.isComplete());
        } catch (RuntimeException ignored) {
            // Preserve the scheduled method's original return value or exception.
        }
    }

    private record Invocation(long id, long start, long threadId, String thread, String task) {
    }
}
