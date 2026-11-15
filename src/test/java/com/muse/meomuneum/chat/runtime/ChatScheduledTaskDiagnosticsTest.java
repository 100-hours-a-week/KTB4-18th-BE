package com.muse.meomuneum.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.ObservationRegistry;

class ChatScheduledTaskDiagnosticsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ChatScheduledTaskDiagnostics.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void disabledUnlessExplicitlyEnabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(ChatScheduledTaskDiagnostics.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(ChatScheduledTaskDiagnostics.class));
        runner.withPropertyValues("chat.scheduler-diagnostics.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ChatScheduledTaskDiagnostics.class));
    }

    @Test
    void capturesExactNanosecondsAndThreadWithoutArgumentsOrMessages() throws Exception {
        var clock = new AtomicLong(1000);
        var handler = new ChatScheduledTaskDiagnostics(clock::get, 1);
        var target = new Target();
        var context = new ScheduledTaskObservationContext(target, Target.class.getMethod("work"));
        assertThat(handler.supportsContext(context)).isTrue();
        handler.onStart(context);
        clock.addAndGet(1_500_500);
        context.setComplete(true);
        handler.onStop(context);
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.getLast().getFormattedMessage()).contains(
                "invocation=1", "task=Target.work", "start_ns=1000", "end_ns=1501500",
                "duration_ns=1500500", "duration_ms=1", "complete=true",
                "thread_id=" + Thread.currentThread().threadId());
        assertThat(appender.list.getLast().getThrowableProxy()).isNull();
        assertThat(handler.supportsContext(new ScheduledTaskObservationContext(new Object(),
                Object.class.getMethod("toString")))).isFalse();
    }

    @Test
    void observesFrameworkRunnableWithoutChangingItsException() throws Exception {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ChatScheduledTaskDiagnostics());
        var target = new Target();
        var runnable = new ScheduledMethodRunnable(target, Target.class.getMethod("fail"), null, () -> registry);
        assertSame(target.failure, assertThrows(IllegalStateException.class, runnable::run));
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.getLast().getFormattedMessage()).contains("complete=false")
                .doesNotContain("private-canary");
        assertThat(appender.list.getLast().getThrowableProxy()).isNull();
    }

    @Test
    void capsInvocationsAndReportsTruncationOnce() throws Exception {
        var handler = new ChatScheduledTaskDiagnostics(System::nanoTime, 1);
        for (int i = 0; i < 5; i++) {
            var context = new ScheduledTaskObservationContext(new Target(), Target.class.getMethod("work"));
            handler.onStart(context);
            handler.onStop(context);
        }
        assertThat(appender.list).hasSize(3);
        assertThat(appender.list.getLast().getFormattedMessage()).contains("chat_scheduled_trace_limit limit=1");
    }

    public static class Target {
        private final IllegalStateException failure = new IllegalStateException("private-canary");

        public void work() {
        }

        public void fail() {
            throw failure;
        }
    }
}
