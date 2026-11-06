package com.muse.meomuneum.chat.message.relay;

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

import com.muse.meomuneum.chat.runtime.ChatRuntimeSchedulingConfig;

class ChatMessageRelaySchedulingConfigTest {
    @Test
    void schedulerExistsOnlyWhenRelayIsEnabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(ChatMessageRelaySchedulingConfig.class)
                .withPropertyValues("chat.message-relay.enabled=false");
        runner.run(context -> assertThat(context).doesNotHaveBean("chatRelayDispatchScheduler"));
        runner.withPropertyValues("chat.message-relay.enabled=true")
                .run(context -> assertThat(context).hasBean("chatRelayDispatchScheduler"));
    }

    @Test
    void actualDispatchAnnotationDoesNotWaitForBlockedSharedMaintenance() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("chat.message-relay.enabled=true", "chat.lifecycle.enabled=true").applyTo(context);
            context.register(ChatMessageRelaySchedulingConfig.class, ChatRuntimeSchedulingConfig.class, Fixtures.class);
            context.refresh();
            Probe probe = context.getBean(Probe.class);
            try {
                assertTrue(probe.sharedStarted.await(3, TimeUnit.SECONDS));
                assertTrue(probe.dispatches.await(2, TimeUnit.SECONDS),
                        "outbox dispatch triggers must continue while shared maintenance is blocked");
                assertThat(probe.thread.get()).startsWith("chat-relay-dispatch-");
                assertThat(probe.release.getCount()).isEqualTo(1);
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
        ChatMessageRelay relay(Probe probe) {
            var relay = mock(ChatMessageRelay.class);
            doAnswer(call -> {
                if (probe.sharedStarted.getCount() == 0 && probe.release.getCount() == 1) {
                    probe.thread.set(Thread.currentThread().getName());
                    probe.dispatches.countDown();
                }
                return null;
            }).when(relay).dispatch();
            return relay;
        }
    }

    static final class Probe {
        private final CountDownLatch sharedStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch dispatches = new CountDownLatch(2);
        private final AtomicReference<String> thread = new AtomicReference<>();
    }

    static final class BlockingJob {
        private final Probe probe;

        BlockingJob(Probe probe) {
            this.probe = probe;
        }

        @Scheduled(fixedDelay = 60000)
        public void run() throws InterruptedException {
            probe.sharedStarted.countDown();
            probe.release.await(5, TimeUnit.SECONDS);
        }
    }
}
