package com.muse.meomuneum.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ChatRuntimeSchedulingConfigTest {
    @Test
    void schedulerIsCreatedOnlyForTheEnabledLifecycle() {
        var runner = new ApplicationContextRunner().withUserConfiguration(ChatRuntimeSchedulingConfig.class);
        runner.run(context -> assertThat(context).doesNotHaveBean("chatRuntimeCheckScheduler"));
        runner.withPropertyValues("chat.lifecycle.enabled=true")
                .run(context -> assertThat(context).hasBean("chatRuntimeCheckScheduler"));
    }

    @Test
    void actualRuntimeCheckKeepsRunningWhileTheDefaultSchedulerIsBlocked() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("chat.lifecycle.enabled=true").applyTo(context);
            context.register(ChatRuntimeSchedulingConfig.class, Fixtures.class);
            context.refresh();
            Probe probe = context.getBean(Probe.class);
            try {
                assertTrue(probe.started.await(5, TimeUnit.SECONDS));
                assertTrue(probe.checksWhileBlocked.await(3, TimeUnit.SECONDS),
                        "runtime check must execute independently of the blocked default scheduler");
                assertThat(probe.release.getCount()).isEqualTo(1);
                assertThat(probe.runtimeThread.get()).startsWith("chat-runtime-check-");
                assertThat(probe.sharedThread.get()).doesNotStartWith("chat-runtime-check-");
            } finally {
                probe.release.countDown();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Fixtures {
        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        BlockingJob blockingJob(Probe probe) {
            return new BlockingJob(probe);
        }

        @Bean
        ChatRuntimeLifecycle lifecycle(Probe probe) {
            // Retain the production class's @Scheduled metadata without performing dependency I/O.
            var runtime = mock(ChatRuntimeLifecycle.class);
            doAnswer(call -> {
                probe.runtimeThread.set(Thread.currentThread().getName());
                if (probe.started.getCount() == 0 && probe.release.getCount() == 1) {
                    probe.checksWhileBlocked.countDown();
                }
                return null;
            }).when(runtime).check();
            return runtime;
        }

        @Bean
        ThreadPoolTaskScheduler anotherScheduler() {
            return new ThreadPoolTaskScheduler();
        }
    }

    static final class Probe {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch checksWhileBlocked = new CountDownLatch(2);
        private final AtomicReference<String> runtimeThread = new AtomicReference<>();
        private final AtomicReference<String> sharedThread = new AtomicReference<>();
    }

    static final class BlockingJob {
        private final Probe probe;

        BlockingJob(Probe probe) {
            this.probe = probe;
        }

        @Scheduled(fixedDelay = 60000)
        public void run() throws InterruptedException {
            probe.sharedThread.set(Thread.currentThread().getName());
            probe.started.countDown();
            probe.release.await(5, TimeUnit.SECONDS);
        }
    }
}
