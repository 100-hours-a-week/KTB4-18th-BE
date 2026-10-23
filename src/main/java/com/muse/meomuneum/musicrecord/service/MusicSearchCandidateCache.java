package com.muse.meomuneum.musicrecord.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;

@Component
public class MusicSearchCandidateCache {
    private static final int MAX_SNAPSHOTS = 64;
    private static final int MAX_CANDIDATES = 2048;
    private final Clock clock;
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>(16, 0.75f, true);

    public MusicSearchCandidateCache(Clock clock) {
        this.clock = clock;
    }

    public synchronized Optional<Snapshot> findQuery(String key) {
        purge();
        String id = snapshots.values().stream().filter(value -> value.key().equals(key))
                .map(Snapshot::id).findFirst().orElse(null);
        return Optional.ofNullable(id == null ? null : snapshots.get(id));
    }

    public synchronized Optional<Snapshot> findSnapshot(String id) {
        purge();
        return Optional.ofNullable(snapshots.get(id));
    }

    public synchronized Snapshot put(String key, String country, List<MusicItem> items) {
        purge();
        var seen = new java.util.HashSet<String>();
        List<MusicItem> immutable = items.stream().filter(item -> seen.add(item.external_music_id()))
                .limit(200).map(item -> new MusicItem(null, "ITUNES", item.external_music_id(), item.title(),
                        item.artist_name(), item.album_cover_url(), item.preview_url(), null, false))
                .toList();
        Instant now = clock.instant();
        Snapshot snapshot = new Snapshot(UUID.randomUUID().toString(), key, immutable, now,
                now.plus(immutable.isEmpty() ? Duration.ofSeconds(30) : Duration.ofMinutes(5)));
        snapshots.values().removeIf(value -> value.key().equals(key));
        snapshots.put(snapshot.id(), snapshot);
        trim(snapshots, MAX_SNAPSHOTS);
        for (MusicItem item : immutable) {
            candidates.put(candidateKey(country, item.external_music_id()),
                    new Candidate(item, now.plus(Duration.ofMinutes(5))));
        }
        trim(candidates, MAX_CANDIDATES);
        return snapshot;
    }

    public synchronized Optional<MusicItem> findCandidate(String country, String externalId) {
        purge();
        Candidate candidate = candidates.get(candidateKey(country, externalId));
        return Optional.ofNullable(candidate == null ? null : candidate.music());
    }

    private String candidateKey(String country, String externalId) {
        return country + ":" + externalId;
    }

    private void purge() {
        Instant now = clock.instant();
        snapshots.values().removeIf(value -> !value.expiresAt().isAfter(now));
        candidates.values().removeIf(value -> !value.expiresAt().isAfter(now));
    }

    private <T> void trim(LinkedHashMap<String, T> values, int limit) {
        while (values.size() > limit) {
            values.remove(values.keySet().iterator().next());
        }
    }

    public record Snapshot(String id, String key, List<MusicItem> items, Instant issuedAt, Instant expiresAt) {
    }

    private record Candidate(MusicItem music, Instant expiresAt) {
    }
}
