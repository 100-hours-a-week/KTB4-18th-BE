package com.muse.meomuneum.musicrecord.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

class MusicSearchStorageContextTest {
    @Test
    void executorIsBoundedAndRejectsInvalidCapacitySettings() {
        var config = new MusicSearchStorageConfig();
        var executor = config.musicSearchStorageExecutor(2, 5, 20);
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(20);
            assertThat(executor.getThreadPoolExecutor().getQueue()).isInstanceOf(LinkedBlockingQueue.class);
        } finally {
            executor.shutdown();
        }

        assertThatThrownBy(() -> config.musicSearchStorageExecutor(0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.musicSearchStorageExecutor(1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheOwningApplicationContextShutsDownItsWorkerExecutor() {
        var config = new MusicSearchStorageConfig();
        var executor = config.musicSearchStorageExecutor(1, 2, 1);
        executor.initialize();
        var owner = new GenericApplicationContext();
        var child = new GenericApplicationContext();
        child.setParent(owner);
        var listener = config.musicSearchStorageShutdown(executor, owner);

        listener.onApplicationEvent(new ContextClosedEvent(child));
        assertThat(executor.getThreadPoolExecutor().isShutdown()).isFalse();

        listener.onApplicationEvent(new ContextClosedEvent(owner));
        assertThat(executor.getThreadPoolExecutor().isTerminated()).isTrue();
    }
}
