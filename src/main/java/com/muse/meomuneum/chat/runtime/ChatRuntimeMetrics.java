package com.muse.meomuneum.chat.runtime;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** All dimensions are bounded outcomes; no account, room, socket, token, body or exception labels. */
public final class ChatRuntimeMetrics {
    private final MeterRegistry registry;
    private final AtomicReference<Sample> sample = new AtomicReference<>(new Sample(Double.NaN, Double.NaN, 0));
    private volatile long sampledAt;
    private final Counter failures;
    private final Counter drainTimeouts;
    private final Counter drainFailures;
    private final Timer drain;

    public record Sample(double pending, double oldestSeconds, double available) {
    }

    public ChatRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
        failures = Counter.builder("chat.runtime.failures").register(registry);
        drainTimeouts = Counter.builder("chat.runtime.drain.timeouts").register(registry);
        drainFailures = Counter.builder("chat.runtime.drain.failures").register(registry);
        drain = Timer.builder("chat.runtime.drain.duration").register(registry);
        Gauge.builder("chat.outbox.pending", sample, value -> value.get().pending()).register(registry);
        Gauge.builder("chat.outbox.oldest.seconds", sample, value -> value.get().oldestSeconds()).register(registry);
        Gauge.builder("chat.db.sample.available", sample, value -> value.get().available()).register(registry);
        Gauge.builder("chat.db.sample.age.seconds", this,
                value -> value.sampledAt == 0 ? Double.NaN : (System.nanoTime() - value.sampledAt) / 1_000_000_000.0)
                .register(registry);
    }

    public void sample(Sample value) {
        sample.set(value);
        sampledAt = System.nanoTime();
    }

    public void failed() {
        failures.increment();
    }

    public void drained(long started, boolean timedOut) {
        drain.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        if (timedOut) {
            drainTimeouts.increment();
        }
    }

    public void drainFailed() {
        drainFailures.increment();
    }

    public void stored(long started, String outcome) {
        Timer.builder("chat.message.store.duration").tag("outcome", outcome).register(registry)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    public void published(long started, boolean success) {
        Timer.builder("chat.redis.publish.duration").tag("outcome", success ? "published" : "unavailable")
                .register(registry).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
}
