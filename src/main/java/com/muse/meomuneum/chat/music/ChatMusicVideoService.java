package com.muse.meomuneum.chat.music;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.music.ChatMusicQueueService.Caller;
import com.muse.meomuneum.chat.music.ChatMusicVideoProvider.Video;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.music.catalog.RequestableMusicRepository;

/** Provider I/O is explicitly separated from all participation/room transactions. */
public final class ChatMusicVideoService {
    private final JdbcTemplate jdbc;
    private final ChatParticipationService participation;
    private final RequestableMusicRepository catalog;
    private final ChatMusicVideoProvider provider;
    private final ChatMusicVideoBudget budget;
    private final int positiveSeconds;
    private final int emptySeconds;
    private final int verificationSeconds;
    private final Map<String, Long> retryAfter = new LinkedHashMap<>();

    public ChatMusicVideoService(JdbcTemplate jdbc, ChatParticipationService participation,
            RequestableMusicRepository catalog, ChatMusicVideoProvider provider, ChatMusicVideoBudget budget,
            int positiveSeconds, int emptySeconds, int verificationSeconds) {
        if (positiveSeconds < 1 || positiveSeconds > 3600 || emptySeconds < 1 || emptySeconds > 300
                || verificationSeconds < 1 || verificationSeconds > 300) {
            throw new IllegalArgumentException("Invalid video cache TTL");
        }
        this.jdbc = jdbc;
        this.participation = participation;
        this.catalog = catalog;
        this.provider = provider;
        this.budget = budget;
        this.positiveSeconds = positiveSeconds;
        this.emptySeconds = emptySeconds;
        this.verificationSeconds = verificationSeconds;
    }

    public Map<String, Object> search(Caller caller, long music) {
        var metadata = authorized(caller, music, () -> metadata(music, false));
        requireEnabled();
        budget.reserve("account-" + caller.userId(), 60, 10);
        long deadline = System.nanoTime() + 8_000_000_000L;
        var lookup = authorized(caller, music, () -> lookup(music, metadata));
        if (lookup.cached() != null) {
            return lookup.cached();
        }
        if (!lookup.ids().isEmpty()) {
            // Expiration ends candidate selection, not our knowledge of the discovered video IDs.
            var verified = provider.verifyAll(lookup.ids());
            var reused = publish(caller, music, lookup, verified, lookup.ids(), false);
            if (reused != null) {
                return reused;
            }
            // Only successful metadata responses confirming no usable saved video permit a new search.
        }
        var fresh = authorized(caller, music, () -> lookup(music, metadata));
        if (fresh.cached() != null) {
            return fresh.cached();
        }
        var videos = provider.search(metadata.title(), metadata.artist(), deadline);
        return publish(caller, music, fresh, videos, List.of(), true);
    }

    private Lookup lookup(long music, Metadata expected) {
        if (!metadata(music, false).equals(expected)) {
            throw new ChatMusicException(409, "MUSIC_METADATA_CHANGED");
        }
        var cached = cached(music, expected.fingerprint());
        if (cached != null) {
            return new Lookup(expected, null, List.of(), Map.of(), cached);
        }
        // Keep up to fifty distinct IDs, including candidates omitted from a later verification batch.
        var ids = jdbc.queryForList("""
                SELECT c.youtube_video_id FROM music_video_candidates c
                JOIN music_video_search_results r ON r.id=c.search_id AND r.music_id=c.music_id
                WHERE r.music_id=? AND r.metadata_fingerprint=? AND r.policy_version=1 AND r.playback_country='KR'
                GROUP BY c.youtube_video_id
                ORDER BY MAX(r.created_at) DESC,MIN(c.rank_no),c.youtube_video_id LIMIT 50
                """, String.class, music, expected.fingerprint());
        if (ids.isEmpty() && jdbc.queryForObject(
                "SELECT COUNT(*) FROM music_video_search_results WHERE music_id=? AND metadata_fingerprint<>?",
                Long.class, music, expected.fingerprint()) == 0) {
            // Legacy catalog/pins have no search fingerprint. Never mix a known different metadata history.
            ids = jdbc.queryForList("""
                    SELECT id FROM (
                      SELECT s.youtube_video_id id,0 priority,s.updated_at seen FROM music_video_sources s
                      JOIN music m ON m.id=s.music_id WHERE s.music_id=?
                        AND BINARY s.youtube_video_id=BINARY m.youtube_video_id
                      UNION ALL
                      SELECT CONVERT(m.youtube_video_id USING ascii) COLLATE ascii_bin,0,UTC_TIMESTAMP(6)
                        FROM music m WHERE m.id=?
                        AND m.youtube_video_id REGEXP '^[A-Za-z0-9_-]{11}$'
                      UNION ALL
                      SELECT youtube_video_id,1,updated_at FROM music_video_sources WHERE music_id=?
                    ) known GROUP BY id ORDER BY MIN(priority),MAX(seen) DESC,id LIMIT 50
                    """, String.class, music, music, music);
        }
        Map<String, Long> versions = new LinkedHashMap<>();
        for (String id : ids) {
            var rows = jdbc.queryForList(
                    "SELECT version FROM music_video_sources WHERE music_id=? AND youtube_video_id=?",
                    Long.class, music, id);
            versions.put(id, rows.isEmpty() ? null : rows.getFirst());
        }
        return new Lookup(expected, jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class),
                List.copyOf(ids), versions, null);
    }

    private Map<String, Object> publish(Caller caller, long music, Lookup lookup, List<Video> videos,
            List<String> verifiedIds, boolean allowEmpty) {
        if (videos == null || videos.size() > ChatMusicVideoProvider.MAX_CANDIDATES
                || videos.stream().anyMatch(Objects::isNull)
                || videos.stream().map(Video::id).distinct().count() != videos.size()
                || (!verifiedIds.isEmpty() && videos.stream().anyMatch(v -> !verifiedIds.contains(v.id())))) {
            throw new ChatMusicException(503, "VIDEO_PROVIDER_UNAVAILABLE");
        }
        return authorized(caller, music, () -> {
            if (!metadata(music, true).equals(lookup.metadata())) {
                throw new ChatMusicException(409, "MUSIC_METADATA_CHANGED");
            }
            var winner = cached(music, lookup.metadata().fingerprint());
            if (winner != null) {
                return winner;
            }
            Map<String, Video> found = new LinkedHashMap<>();
            videos.forEach(v -> found.put(v.id(), v));
            var checked = new LinkedHashSet<>(verifiedIds);
            checked.addAll(found.keySet());
            List<Video> usable = new ArrayList<>();
            for (String id : checked.stream().sorted().toList()) {
                var video = found.get(id);
                var versions = jdbc.query("""
                        SELECT version,updated_at FROM music_video_sources
                        WHERE music_id=? AND youtube_video_id=? FOR UPDATE
                        """, (r, i) -> new SourceVersion(r.getLong(1), r.getObject(2, LocalDateTime.class)), music, id);
                if (manuallyDisabled(music, id)) {
                    continue;
                }
                if (!versions.isEmpty()) {
                    var current = versions.getFirst();
                    Long expected = lookup.versions().get(id);
                    if ((lookup.versions().containsKey(id)
                            ? !Objects.equals(expected, current.version())
                            : current.updated().isAfter(lookup.startedAt()))) {
                        throw new ChatMusicException(503, "VIDEO_VERIFICATION_REQUIRED");
                    }
                    if (jdbc.update("""
                            UPDATE music_video_sources SET playback_status=?,channel_id=COALESCE(?,channel_id),
                              duration_millis=COALESCE(?,duration_millis),verified_at=UTC_TIMESTAMP(6),
                              valid_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),version=version+1,
                              updated_at=UTC_TIMESTAMP(6) WHERE music_id=? AND youtube_video_id=? AND version=?
                              AND operator_blocked=FALSE AND version<9007199254740991
                            """, video == null ? "UNAVAILABLE" : "PLAYABLE", video == null ? null : video.channelId(),
                            video == null ? null : video.durationMillis(), verificationSeconds, music, id,
                            current.version()) != 1) {
                        throw new ChatMusicException(503, "VIDEO_VERIFICATION_REQUIRED");
                    }
                } else if (video != null) {
                    var reviews = jdbc.queryForList("""
                            SELECT review_reference,reviewed_by FROM music_playback_sources
                            WHERE music_id=? AND BINARY youtube_video_id=BINARY ? AND channel_id=?
                            """, music, id, video.channelId());
                    var review = reviews.isEmpty() ? null : reviews.getFirst();
                    jdbc.update("""
                            INSERT INTO music_video_sources(music_id,youtube_video_id,channel_id,playback_status,
                              duration_millis,verified_at,valid_until,official_status,review_reference,reviewed_by)
                            VALUES (?,?,?,'PLAYABLE',?,UTC_TIMESTAMP(6),TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),?,?,?)
                            """, music, id, video.channelId(), video.durationMillis(), verificationSeconds,
                            review == null ? "UNREVIEWED" : "CONFIRMED",
                            review == null ? null : review.get("review_reference"),
                            review == null ? null : review.get("reviewed_by"));
                }
                if (video != null) {
                    usable.add(video);
                }
            }
            if (usable.isEmpty() && !allowEmpty) {
                return null;
            }
            // Preserve provider/discovery ordering, independently of the deterministic source lock order.
            usable.sort(java.util.Comparator.comparingInt(v -> videos.indexOf(v)));
            String search = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO music_video_search_results(id,music_id,metadata_fingerprint,policy_version,
                      result_kind,expires_at) VALUES (?,?,?,1,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))
                    """, search, music, lookup.metadata().fingerprint(), usable.isEmpty() ? "EMPTY" : "READY",
                    usable.isEmpty() ? emptySeconds : positiveSeconds);
            int rank = 0;
            for (var video : usable) {
                jdbc.update("""
                        INSERT INTO music_video_candidates(id,search_id,music_id,youtube_video_id,rank_no,title,
                          channel_title,thumbnail_url,duration_millis) VALUES (?,?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID().toString(), search, music, video.id(), ++rank, video.title(),
                        video.channelTitle(), video.thumbnailUrl(), video.durationMillis());
            }
            return result(search);
        });
    }

    private record Lookup(Metadata metadata, LocalDateTime startedAt, List<String> ids, Map<String, Long> versions,
            Map<String, Object> cached) {
    }

    private record SourceVersion(long version, LocalDateTime updated) {
    }

    /** Preflight only. Successful receipt replay is checked by the queue BEFORE this method. */
    public void prepare(Caller caller, long music, String candidate) {
        String video = authorized(caller, music, () -> candidate(music, candidate));
        requireEnabled();
        refresh(music, video);
    }

    /** Called inside ADD's final transaction, after locking the music row and rechecking provenance. */
    public Selection selection(long music, String candidate) {
        String video = candidate(music, candidate);
        var sources = jdbc.query("""
                SELECT s.duration_millis,s.playback_status,s.valid_until>UTC_TIMESTAMP(6)
                  AND s.verified_at<=UTC_TIMESTAMP(6) AND s.verified_at>TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) fresh
                FROM music_video_sources s WHERE s.music_id=? AND s.youtube_video_id=? FOR SHARE
                """, (r, i) -> new Object[]{r.getObject(1, Long.class), r.getString(2), r.getBoolean(3)},
                -verificationSeconds, music, video);
        if (!sources.isEmpty() && ("UNAVAILABLE".equals(sources.getFirst()[1]) || manuallyDisabled(music, video))) {
            throw new ChatMusicException(409, "VIDEO_UNAVAILABLE");
        }
        if (sources.isEmpty() || !Boolean.TRUE.equals(sources.getFirst()[2])) {
            throw new ChatMusicException(503, "VIDEO_VERIFICATION_REQUIRED");
        }
        if (!"PLAYABLE".equals(sources.getFirst()[1]) || manuallyDisabled(music, video)) {
            throw new ChatMusicException(409, "VIDEO_UNAVAILABLE");
        }
        return new Selection(video, (Long) sources.getFirst()[0]);
    }

    public record Selection(String video, long duration) {
    }

    public int verificationMaxAgeSeconds() {
        return provider == null ? 0 : verificationSeconds;
    }

    private String candidate(long music, String candidate) {
        var rows = jdbc.queryForList("""
                SELECT c.youtube_video_id,r.metadata_fingerprint,r.expires_at>UTC_TIMESTAMP(6) fresh
                FROM music_video_candidates c JOIN music_video_search_results r ON r.id=c.search_id
                WHERE c.id=? AND c.music_id=? AND r.policy_version=1 AND r.playback_country='KR'
                """, candidate, music);
        if (rows.isEmpty()) {
            throw new ChatMusicException(400, "INVALID_CANDIDATE");
        }
        var row = rows.getFirst();
        if (!Boolean.TRUE.equals(row.get("fresh")) && !(row.get("fresh") instanceof Number n && n.intValue() == 1)) {
            throw new ChatMusicException(410, "CANDIDATE_EXPIRED");
        }
        if (!metadata(music, false).fingerprint().equals(row.get("metadata_fingerprint"))) {
            throw new ChatMusicException(409, "MUSIC_METADATA_CHANGED");
        }
        return (String) row.get("youtube_video_id");
    }

    /** Refresh one pending pin outside locks. Temporary failures preserve the queue, with bounded backoff. */
    public void prepareRoom(long room) {
        prepareRoom(room, false);
    }

    public void prepareRoom(long room, boolean includeNext) {
        if (provider == null) {
            return;
        }
        var pins = jdbc.queryForList("""
                SELECT q.music_id,q.source_video_id FROM music_queue_items q
                JOIN playback_states p ON p.chat_room_id=q.room_id
                WHERE q.room_id=? AND q.source_video_id IS NOT NULL
                  AND ((q.status='WAITING' AND (? OR p.state='IDLE' OR
                    (p.state='PLAYING' AND p.due_at<=UTC_TIMESTAMP(6))))
                    OR (q.status='PLAYING' AND p.state='PAUSED'))
                ORDER BY q.sequence_no LIMIT 1
                """, room, includeNext);
        for (var pin : pins) {
            long music = ((Number) pin.get("music_id")).longValue();
            String video = (String) pin.get("source_video_id");
            String key = music + ":" + video;
            synchronized (retryAfter) {
                Long deadline = retryAfter.get(key);
                if (deadline != null && deadline - System.nanoTime() > 0) {
                    continue;
                }
            }
            try {
                refresh(music, video);
                synchronized (retryAfter) {
                    retryAfter.remove(key);
                }
            } catch (ChatMusicException temporary) {
                synchronized (retryAfter) {
                    if (retryAfter.size() >= 1024) {
                        retryAfter.remove(retryAfter.keySet().iterator().next());
                    }
                    retryAfter.put(key, System.nanoTime() + 60_000_000_000L);
                }
            }
        }
    }

    private void refresh(long music, String video) {
        var rows = jdbc.queryForList("""
                SELECT version,valid_until>UTC_TIMESTAMP(6) AND verified_at<=UTC_TIMESTAMP(6)
                  AND verified_at>TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) fresh
                FROM music_video_sources WHERE music_id=? AND youtube_video_id=?
                """, -verificationSeconds, music, video);
        if (rows.isEmpty() || manuallyDisabled(music, video)) {
            return;
        }
        var row = rows.getFirst();
        if (Boolean.TRUE.equals(row.get("fresh")) || row.get("fresh") instanceof Number n && n.intValue() == 1) {
            return;
        }
        var verified = provider.verify(video);
        if (verified != null && !video.equals(verified.id())) {
            throw new ChatMusicException(503, "VIDEO_PROVIDER_UNAVAILABLE");
        }
        jdbc.update("""
                UPDATE music_video_sources SET playback_status=?,channel_id=COALESCE(?,channel_id),
                  duration_millis=COALESCE(?,duration_millis),verified_at=UTC_TIMESTAMP(6),
                  valid_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE music_id=? AND youtube_video_id=? AND version=? AND version<9007199254740991
                  AND operator_blocked=FALSE
                """, verified == null ? "UNAVAILABLE" : "PLAYABLE", verified == null ? null : verified.channelId(),
                verified == null ? null : verified.durationMillis(), verificationSeconds, music, video,
                row.get("version"));
    }

    private boolean manuallyDisabled(long music, String video) {
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM music_video_sources
                WHERE music_id=? AND youtube_video_id=? AND operator_blocked=TRUE
                """, Long.class, music, video) > 0) {
            return true;
        }
        return jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM music_playback_sources WHERE music_id=? AND youtube_video_id=? AND
                        status='UNAVAILABLE'
                        """,
                Long.class, music, video) > 0;
    }

    private Map<String, Object> cached(long music, String fingerprint) {
        var ids = jdbc.queryForList("""
                SELECT r.id FROM music_video_search_results r WHERE r.music_id=? AND r.metadata_fingerprint=?
                  AND r.policy_version=1 AND r.playback_country='KR' AND r.expires_at>UTC_TIMESTAMP(6)
                  AND (r.result_kind='EMPTY' OR NOT EXISTS(
                    SELECT 1 FROM music_video_candidates c JOIN music_video_sources s
                      ON s.music_id=c.music_id AND s.youtube_video_id=c.youtube_video_id
                    WHERE c.search_id=r.id AND (s.playback_status<>'PLAYABLE' OR s.operator_blocked=TRUE
                      OR s.verified_at IS NULL OR s.valid_until<=UTC_TIMESTAMP(6) OR s.verified_at>UTC_TIMESTAMP(6)
                      OR s.verified_at<=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6))
                      OR EXISTS(SELECT 1 FROM music_playback_sources p WHERE p.music_id=s.music_id
                        AND p.youtube_video_id=s.youtube_video_id AND p.status='UNAVAILABLE'))))
                ORDER BY r.created_at DESC,r.id DESC LIMIT 1 FOR SHARE
                """, String.class, music, fingerprint, -verificationSeconds);
        return ids.isEmpty() ? null : result(ids.getFirst());
    }

    private Map<String, Object> result(String search) {
        var row = jdbc.queryForMap(
                "SELECT id search_id,music_id,result_kind,expires_at FROM music_video_search_results "
                        + "WHERE id=? FOR SHARE",
                search);
        row.put("expires_at", ((LocalDateTime) row.get("expires_at")).toInstant(ZoneOffset.UTC).toString());
        var items = jdbc.queryForList(
                """
                        SELECT c.id candidate_id,c.music_id,c.youtube_video_id video_id,c.title,c.channel_title,
                        c.thumbnail_url,c.duration_millis,
                          s.channel_id,'PLAYABLE' playback_status,s.official_status FROM music_video_candidates
                          c JOIN music_video_sources s
                          ON s.music_id=c.music_id AND s.youtube_video_id=c.youtube_video_id
                        WHERE c.search_id=? ORDER BY c.rank_no FOR SHARE
                        """,
                search);
        items.forEach(item -> item.put("expires_at", row.get("expires_at")));
        row.put("items", items);
        return row;
    }

    private <T> T authorized(Caller c, long music, Supplier<T> work) {
        return participation.musicTransaction(c.userId(), c.sid(), c.roomId(), c.membershipId(), c.viewId(), tx -> {
            long region = jdbc.queryForObject("SELECT region_id FROM chat_rooms WHERE id=?", Long.class, c.roomId());
            if (catalog.findProvenance(c.userId(), region, music).isEmpty()) {
                throw new ChatMusicException(409, "MUSIC_NOT_REQUESTABLE");
            }
            return work.get();
        });
    }

    private Metadata metadata(long music, boolean lock) {
        var rows = jdbc.query("""
                SELECT COALESCE(NULLIF(TRIM(title_ko),''),NULLIF(TRIM(title_us),''),title),
                  COALESCE(NULLIF(TRIM(artist_name_ko),''),NULLIF(TRIM(artist_name_us),''),artist_name),
                  title,artist_name,title_ko,artist_name_ko,title_us,artist_name_us FROM music WHERE id=?
                """ + (lock ? " FOR UPDATE" : ""), (r, i) -> {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                for (int column = 1; column <= 8; column++) {
                    String value = r.getString(column);
                    byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
                return new Metadata(r.getString(1), r.getString(2), HexFormat.of().formatHex(digest.digest()));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }, music);
        if (rows.isEmpty()) {
            throw new ChatMusicException(409, "MUSIC_NOT_REQUESTABLE");
        }
        return rows.getFirst();
    }

    private void requireEnabled() {
        if (provider == null) {
            throw new ChatMusicException(503, "VIDEO_SEARCH_DISABLED");
        }
    }

    private record Metadata(String title, String artist, String fingerprint) {
    }
}
