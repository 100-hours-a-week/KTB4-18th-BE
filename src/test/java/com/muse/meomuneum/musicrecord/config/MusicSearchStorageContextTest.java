package com.muse.meomuneum.musicrecord.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.musicrecord.service.MusicSearchStorageService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class MusicSearchStorageContextTest {
    @Test
    void searchReturnsBeforeWorkerAndStoresEntireImmutableDeduplicatedRawSnapshot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(25);
        var stored = new CopyOnWriteArrayList<MusicItem>();
        var threads = new CopyOnWriteArrayList<String>();
        var repository = mock(MusicRecordRepository.class);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            stored.add(invocation.getArgument(0));
            threads.add(Thread.currentThread().getName());
            done.countDown();
            return null;
        }).when(repository).insertMusicIfAbsent(any());
        var raw = new ArrayList<MusicItem>();
        for (int i = 1; i <= 25; i++) {
            raw.add(item(String.valueOf(i)));
        }
        raw.add(raw.getFirst());
        var itunes = mock(ItunesMusicSearchClient.class);
        when(itunes.search("song")).thenReturn(raw);
        try (var context = context(repository, new CopyOnWriteArrayList<>(), 5)) {
            var storage = context.getBean(MusicSearchStorageService.class);
            assertThat(AopUtils.isAopProxy(storage)).isTrue();
            var service = search(repository, itunes, storage);
            var response = service.search("song", "ITUNES", null, 20);
            assertThat(response.items()).hasSize(20);
            assertThat(response.has_next()).isTrue();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(done.getCount()).isEqualTo(25);
            raw.clear();
            release.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stored).hasSize(25).extracting(MusicItem::external_music_id)
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 25)
                            .mapToObj(String::valueOf).toList());
            assertThat(threads).allMatch(name -> name.startsWith("music-search-storage-"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void boundedQueueRejectsThirdSearchWithoutCallerThreadDatabaseWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(2);
        var repository = mock(MusicRecordRepository.class);
        var workerThreads = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            workerThreads.add(Thread.currentThread().getName());
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            completed.countDown();
            return null;
        }).when(repository).insertMusicIfAbsent(any());
        var logger = (Logger) LoggerFactory.getLogger(MusicRecordService.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (var context = context(repository, new CopyOnWriteArrayList<>(), 5)) {
            var itunes = mock(ItunesMusicSearchClient.class);
            when(itunes.search("song")).thenReturn(List.of(item("1")));
            var search = search(repository, itunes, context.getBean(MusicSearchStorageService.class));
            search.search("song", "ITUNES", null, 20);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            search.search("song", "ITUNES", null, 20);
            var response = search.search("song", "ITUNES", null, 20);
            assertThat(response.items()).containsExactly(item("1"));
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("event=music_search_storage_rejected"));
            assertThat(workerThreads).hasSize(1).allMatch(name -> name.startsWith("music-search-storage-"));
            release.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void closeRejectsNewTasksAndDrainsRunningAndQueuedBeforeDestroyingDatabaseResources() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        var repository = mock(MusicRecordRepository.class);
        var count = new AtomicInteger();
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).doesNotContain("dataSource-destroy", "transactionManager-destroy");
            events.add("stored-" + count.incrementAndGet());
            return null;
        }).when(repository).insertMusicIfAbsent(any());
        var context = context(repository, events, 5);
        var executor = context.getBean("musicSearchStorageExecutor", ThreadPoolTaskExecutor.class);
        var storage = context.getBean(MusicSearchStorageService.class);
        var closed = new CountDownLatch(1);
        var closer = new Thread(() -> {
            context.close();
            closed.countDown();
        });
        try {
            assertThat(context.getBeanFactory().getBeanDefinition("musicSearchStorageExecutor").getDependsOn())
                    .containsExactly("dataSource", "transactionManager");
            storage.store(List.of(item("1")));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            storage.store(List.of(item("2")));
            closer.start();
            awaitShutdown(executor);
            assertThatThrownBy(() -> storage.store(List.of(item("3"))))
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(closed.getCount()).isEqualTo(1);
            assertThat(events).doesNotContain("dataSource-destroy", "transactionManager-destroy");
            release.countDown();
            assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).containsSubsequence("stored-1", "stored-2", "transactionManager-destroy");
            assertThat(events.indexOf("dataSource-destroy")).isGreaterThan(events.indexOf("stored-2"));
            assertThat(executor.getThreadPoolExecutor().isTerminated()).isTrue();
        } finally {
            release.countDown();
            closer.join(6000);
            context.close();
        }
    }

    @Test
    void shutdownTimeoutWarnsAndReturnsBeforeBlockedTaskFinishes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var repository = mock(MusicRecordRepository.class);
        doAnswer(invocation -> {
            entered.countDown();
            release.await();
            finished.countDown();
            return null;
        }).when(repository).insertMusicIfAbsent(any());
        var logger = (Logger) LoggerFactory.getLogger(ThreadPoolTaskExecutor.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        var context = context(repository, new CopyOnWriteArrayList<>(), 1);
        var executor = context.getBean("musicSearchStorageExecutor", ThreadPoolTaskExecutor.class);
        try {
            context.getBean(MusicSearchStorageService.class).store(List.of(item("1")));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            context.close();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(900L, 4000L);
            assertThat(finished.getCount()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().isTerminated()).isFalse();
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("Timed out while waiting for executor"));
        } finally {
            release.countDown();
            assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            context.close();
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void closingChildContextDoesNotShutdownParentExecutor() {
        try (var parent = context(mock(MusicRecordRepository.class), new CopyOnWriteArrayList<>(), 1)) {
            var executor = parent.getBean("musicSearchStorageExecutor", ThreadPoolTaskExecutor.class);
            try (var child = new AnnotationConfigApplicationContext()) {
                child.setParent(parent);
                child.refresh();
            }
            assertThat(executor.getThreadPoolExecutor().isShutdown()).isFalse();
        }
    }

    private AnnotationConfigApplicationContext context(MusicRecordRepository repository, List<String> events,
            int awaitSeconds) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("storage-test", Map.of(
                "music.search-storage.workers", "1", "music.search-storage.queue-capacity", "1",
                "music.search-storage.shutdown-await-seconds", String.valueOf(awaitSeconds),
                "music.search-storage.retry-delay", "10ms")));
        context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
        context.registerBean("dataSource", Resource.class, () -> new Resource(events));
        context.registerBean("transactionManager", Transactions.class, () -> new Transactions(events));
        context.registerBean(MusicRecordRepository.class, () -> repository);
        context.register(MusicSearchStorageConfig.class, MusicSearchStorageService.class);
        context.refresh();
        return context;
    }

    private MusicRecordService search(MusicRecordRepository repository, ItunesMusicSearchClient itunes,
            MusicSearchStorageService storage) {
        return new MusicRecordService(repository, itunes, mock(LocationResolutionTokenProvider.class),
                new MusicSearchCursorCodec("test-secret"), storage);
    }

    private MusicItem item(String id) {
        return new MusicItem(null, "ITUNES", id, "song", "artist", null, null, null, false);
    }

    private void awaitShutdown(ThreadPoolTaskExecutor executor) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!executor.getThreadPoolExecutor().isShutdown() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(executor.getThreadPoolExecutor().isShutdown()).isTrue();
    }

    static class Resource implements DisposableBean {
        private final List<String> events;
        Resource(List<String> events) {
            this.events = events;
        }
        @Override
        public void destroy() {
            events.add("dataSource-destroy");
        }
    }

    static class Transactions extends AbstractPlatformTransactionManager implements DisposableBean {
        private final List<String> events;
        Transactions(List<String> events) {
            this.events = events;
        }
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
        @Override
        public void destroy() {
            events.add("transactionManager-destroy");
        }
    }
}
