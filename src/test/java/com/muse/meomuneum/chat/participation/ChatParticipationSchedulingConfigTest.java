package com.muse.meomuneum.chat.participation;

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

import com.muse.meomuneum.chat.runtime.ChatRuntimeLifecycle;
import com.muse.meomuneum.chat.runtime.ChatRuntimeSchedulingConfig;

class ChatParticipationSchedulingConfigTest {
    @Test
    void heartbeatSchedulerMatchesParticipationFlagIndependentlyOfRuntimeLifecycle() {
        var runner = new ApplicationContextRunner().withUserConfiguration(
                ChatParticipationSchedulingConfig.class, ChatRuntimeSchedulingConfig.class);
        runner.run(context -> assertThat(context).doesNotHaveBean("chatServerHeartbeatScheduler"));
        runner.withPropertyValues("chat.distributed-presence.enabled=true").run(context -> {
            assertThat(context).hasBean("chatServerHeartbeatScheduler");
            assertThat(context).doesNotHaveBean("chatRuntimeCheckScheduler");
        });
        runner.withPropertyValues("chat.distributed-presence.enabled=false", "chat.lifecycle.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("chatServerHeartbeatScheduler");
                    assertThat(context).hasBean("chatRuntimeCheckScheduler");
                });
    }

    @Test
    void heartbeatAndRuntimeCheckRemainIndependentOfEachOtherAndBlockedSharedWork() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("chat.distributed-presence.enabled=true", "chat.lifecycle.enabled=true")
                    .applyTo(context);
            context.register(ChatParticipationSchedulingConfig.class, ChatRuntimeSchedulingConfig.class,
                    Fixtures.class);
            context.refresh();
            Probe probe = context.getBean(Probe.class);
            try {
                assertTrue(probe.sharedStarted.await(5, TimeUnit.SECONDS));
                assertTrue(probe.heartbeatStarted.await(7, TimeUnit.SECONDS),
                        "server heartbeat must not wait for the default scheduler");
                assertTrue(probe.checksDuringBoth.await(3, TimeUnit.SECONDS),
                        "runtime checks must not wait for heartbeat DB work");
                assertThat(probe.releaseShared.getCount()).isEqualTo(1);
                assertThat(probe.releaseHeartbeat.getCount()).isEqualTo(1);
                assertThat(probe.heartbeatThread.get()).startsWith("chat-server-heartbeat-");
                assertThat(probe.checkThread.get()).startsWith("chat-runtime-check-");
                assertThat(probe.sharedThread.get()).doesNotStartWith("chat-server-heartbeat-")
                        .doesNotStartWith("chat-runtime-check-");
            } finally {
                probe.releaseHeartbeat.countDown();
                probe.releaseShared.countDown();
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
        ChatParticipationCoordinator coordinator(Probe probe) {
            // Use the actual production scheduling annotations without dependency I/O.
            var coordinator = mock(ChatParticipationCoordinator.class);
            doAnswer(call -> {
                probe.heartbeatThread.set(Thread.currentThread().getName());
                probe.heartbeatStarted.countDown();
                probe.releaseHeartbeat.await(12, TimeUnit.SECONDS);
                return null;
            }).when(coordinator).heartbeat();
            return coordinator;
        }

        @Bean
        ChatRuntimeLifecycle lifecycle(Probe probe) {
            var lifecycle = mock(ChatRuntimeLifecycle.class);
            doAnswer(call -> {
                probe.checkThread.set(Thread.currentThread().getName());
                if (probe.sharedStarted.getCount() == 0 && probe.heartbeatStarted.getCount() == 0
                        && probe.releaseShared.getCount() == 1 && probe.releaseHeartbeat.getCount() == 1) {
                    probe.checksDuringBoth.countDown();
                }
                return null;
            }).when(lifecycle).check();
            return lifecycle;
        }
    }

    static final class Probe {
        private final CountDownLatch sharedStarted = new CountDownLatch(1);
        private final CountDownLatch heartbeatStarted = new CountDownLatch(1);
        private final CountDownLatch releaseShared = new CountDownLatch(1);
        private final CountDownLatch releaseHeartbeat = new CountDownLatch(1);
        private final CountDownLatch checksDuringBoth = new CountDownLatch(2);
        private final AtomicReference<String> sharedThread = new AtomicReference<>();
        private final AtomicReference<String> heartbeatThread = new AtomicReference<>();
        private final AtomicReference<String> checkThread = new AtomicReference<>();
    }

    static final class BlockingJob {
        private final Probe probe;

        BlockingJob(Probe probe) {
            this.probe = probe;
        }

        @Scheduled(fixedDelay = 60000)
        public void run() throws InterruptedException {
            probe.sharedThread.set(Thread.currentThread().getName());
            probe.sharedStarted.countDown();
            probe.releaseShared.await(12, TimeUnit.SECONDS);
        }
    }
}
