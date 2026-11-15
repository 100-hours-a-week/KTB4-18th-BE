package com.muse.meomuneum.musicrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;

class MusicSearchCandidateCacheTest {
    private final MutableClock clock = new MutableClock();
    private final MusicSearchCandidateCache cache = new MusicSearchCandidateCache(clock);

    @Test
    void snapshotsAreCappedDeduplicatedImmutableAndExpireWithoutSliding() {
        var raw = new java.util.ArrayList<>(IntStream.range(1, 205).mapToObj(i -> item(i)).toList());
        raw.addFirst(item(1));
        var snapshot = cache.put("key", "KR", raw);
        raw.clear();
        assertThat(snapshot.items()).hasSize(200);
        assertThat(snapshot.items()).extracting(MusicItem::music_id).containsOnlyNulls();
        assertThatThrownBy(() -> snapshot.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        clock.now = clock.now.plusSeconds(299);
        assertThat(cache.findSnapshot(snapshot.id())).isPresent();
        assertThat(cache.findCandidate("KR", "1")).isPresent();
        assertThat(cache.findCandidate("US", "1")).isEmpty();
        clock.now = clock.now.plusSeconds(1);
        assertThat(cache.findSnapshot(snapshot.id())).isEmpty();
        assertThat(cache.findCandidate("KR", "1")).isEmpty();
    }

    @Test
    void emptyResultsExpireAtThirtySecondsAndLruIsBounded() {
        var empty = cache.put("empty", "KR", List.of());
        clock.now = clock.now.plusSeconds(30);
        assertThat(cache.findSnapshot(empty.id())).isEmpty();
        var first = cache.put("0", "KR", List.of(item(1)));
        for (int i = 1; i < 64; i++) {
            cache.put("" + i, "KR", List.of(item(i + 1)));
        }
        assertThat(cache.findSnapshot(first.id())).isPresent();
        cache.put("64", "KR", List.of(item(65)));
        assertThat(cache.findQuery("1")).isEmpty();
        assertThat(cache.findSnapshot(first.id())).isPresent();
    }

    @Test
    void candidateStoreIsBoundedIndependentlyOfSnapshotEviction() {
        for (int i = 0; i < 12; i++) {
            int offset = i * 200;
            cache.put("q" + i, "KR", IntStream.range(1, 201).mapToObj(n -> item(offset + n)).toList());
        }
        assertThat(cache.findCandidate("KR", "352")).isEmpty();
        assertThat(cache.findCandidate("KR", "353")).isPresent();
        assertThat(cache.findCandidate("KR", "2400")).isPresent();
    }

    private MusicItem item(int id) {
        return new MusicItem(99L, "ITUNES", "" + id, "title", "artist", null, null, "abcdefghijk", true);
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-22T00:00:00Z");
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
        @Override
        public Instant instant() {
            return now;
        }
    }
}
