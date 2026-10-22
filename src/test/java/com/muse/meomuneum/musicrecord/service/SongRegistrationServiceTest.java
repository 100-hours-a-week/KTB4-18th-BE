package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Request;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;

class SongRegistrationServiceTest {
    private final MusicRecordRepository repository = mock(MusicRecordRepository.class);
    private final ItunesMusicSearchClient itunes = mock(ItunesMusicSearchClient.class);
    private final MusicSearchCandidateCache cache = new MusicSearchCandidateCache(Clock.systemUTC());
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    private SongRegistrationService service() {
        when(itunes.country()).thenReturn("KR");
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        return new SongRegistrationService(repository, itunes, cache, manager);
    }

    @Test
    void existingSongIsReturnedUntouchedWithoutLookupOrInsert() {
        var service = service();
        var stored = item(1L, "1");
        when(repository.findMusic("ITUNES", "1")).thenReturn(Optional.of(stored));
        assertThat(service.register(new Request("1")).music()).isSameAs(stored);
        assertThat(service.register(new Request("1")).created()).isFalse();
        verify(itunes, never()).lookup(any());
        verify(repository, never()).insertSelectedMusic(any());
        org.mockito.Mockito.verifyNoInteractions(manager);
    }

    @Test
    void trustedCandidateAvoidsLookupAndOnlyCommittedInsertIsCreated() {
        var service = service();
        cache.put("key", "KR", java.util.List.of(item(null, "1")));
        when(repository.insertSelectedMusic(item(null, "1"))).thenReturn(item(42L, "1"));
        var result = service.register(new Request("1"));
        assertThat(result.created()).isTrue();
        assertThat(result.music().music_id()).isEqualTo(42);
        verify(itunes, never()).lookup(any());
        verify(manager).commit(any());
    }

    @Test
    void duplicateRollsBackBeforeFreshReadAndOtherErrorsAreNotSuppressed() {
        var service = service();
        when(repository.findMusic("ITUNES", "1")).thenReturn(Optional.empty(), Optional.empty(),
                Optional.of(item(7L, "1")));
        when(itunes.lookup("1")).thenReturn(Optional.of(item(null, "1")));
        when(repository.insertSelectedMusic(any())).thenThrow(new DuplicateKeyException("duplicate"));
        assertThat(service.register(new Request("1")).created()).isFalse();
        var order = org.mockito.Mockito.inOrder(manager, repository);
        order.verify(repository, org.mockito.Mockito.times(2)).findMusic("ITUNES", "1");
        order.verify(manager).getTransaction(any());
        order.verify(repository).insertSelectedMusic(any());
        order.verify(manager).rollback(any());
        order.verify(manager).getTransaction(any());
        order.verify(repository).findMusic("ITUNES", "1");
        order.verify(manager).commit(any());
    }

    @Test
    void failedLookupClearsInflightSoRetryCanSucceed() {
        var service = service();
        when(itunes.lookup("1")).thenThrow(new MusicRecordException("provider", HttpStatus.BAD_GATEWAY,
                "music provider unavailable")).thenReturn(Optional.of(item(null, "1")));
        when(repository.insertSelectedMusic(any())).thenReturn(item(1L, "1"));
        assertThatThrownBy(() -> service.register(new Request("1"))).hasMessage("music provider unavailable");
        assertThat(service.register(new Request("1")).created()).isTrue();
    }

    @Test
    void concurrentSameKeyCoalescesLookupAndInsertButFollowersAreNotCreated() throws Exception {
        var service = service();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(itunes.lookup("1")).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(item(null, "1"));
        });
        when(repository.insertSelectedMusic(any())).thenReturn(item(42L, "1"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var owner = executor.submit(() -> service.register(new Request("1")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var followerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var follower = executor.submit(() -> {
                followerThread.set(Thread.currentThread());
                return service.register(new Request("1"));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline && (followerThread.get() == null
                    || followerThread.get().getState() != Thread.State.WAITING)) {
                Thread.sleep(5);
            }
            assertThat(followerThread.get().getState()).isEqualTo(Thread.State.WAITING);
            release.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS).created()).isTrue();
            var followed = follower.get(5, TimeUnit.SECONDS);
            assertThat(followed.music().music_id()).isEqualTo(42);
            assertThat(followed.created()).isFalse();
        } finally {
            release.countDown();
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void inFlightKeysAreBoundedAt64AndRecoverAfterCompletion() throws Exception {
        var service = service();
        var entered = new CountDownLatch(64);
        var release = new CountDownLatch(1);
        when(itunes.lookup(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return Optional.of(item(null, invocation.getArgument(0)));
        });
        when(repository.insertSelectedMusic(any())).thenAnswer(invocation -> {
            MusicItem selected = invocation.getArgument(0);
            return item(Long.valueOf(selected.external_music_id()), selected.external_music_id());
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 1; i <= 64; i++) {
                String id = "" + i;
                futures.add(executor.submit(() -> service.register(new Request(id))));
            }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.register(new Request("65")))
                        .isInstanceOfSatisfying(MusicRecordException.class,
                                failure -> assertThat(failure.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            } finally {
                release.countDown();
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertThat(service.register(new Request("65")).created()).isTrue();
        } finally {
            release.countDown();
        }
    }

    private MusicItem item(Long id, String externalId) {
        return new MusicItem(id, "ITUNES", externalId, "title", "artist", null, null, null, false);
    }
}
