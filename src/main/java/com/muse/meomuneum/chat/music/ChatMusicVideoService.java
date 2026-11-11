package com.muse.meomuneum.chat.music;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.music.ChatMusicQueueService.Caller;
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
        var cached = authorized(caller, music, () -> {
            if (!metadata(music, false).equals(metadata)) {
                throw new ChatMusicException(409, "MUSIC_METADATA_CHANGED");
            }
            return cached(music, metadata.fingerprint());
        });
        if (cached != null) {
            return cached;
        }
        // Two bounded HTTP calls at most, with no database transaction held.
        var videos = provider.search(metadata.title(), metadata.artist());
        if (videos.size() > 10) {
            throw new ChatMusicException(503, "VIDEO_PROVIDER_UNAVAILABLE");
        }
        return authorized(caller, music, () -> {
            if (!metadata(music, true).equals(metadata)) {
                throw new ChatMusicException(409, "MUSIC_METADATA_CHANGED");
            }
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO music_video_search_results(id,music_id,metadata_fingerprint,policy_version,
                      result_kind,expires_at) VALUES (?,?,?,1,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))
                    """, id, music, metadata.fingerprint(), videos.isEmpty() ? "EMPTY" : "READY",
                    videos.isEmpty() ? emptySeconds : positiveSeconds);
            int rank = 0;
            for (var video : videos) {
                // An older concurrent search must never replace a later verification or a manual decision.
                jdbc.update("""
                        INSERT IGNORE INTO music_video_sources(music_id,youtube_video_id,channel_id,playback_status,
                          duration_millis,verified_at,valid_until)
                        VALUES (?,?,?,'PLAYABLE',?,UTC_TIMESTAMP(6),TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))
                        """, music, video.id(), video.channelId(), video.durationMillis(), verificationSeconds);
                // Historical active pins may have no channel metadata. Fill the display field without
                // overwriting a later availability decision or inventing an official review.
                jdbc.update("""
                        UPDATE music_video_sources SET channel_id=?,version=version+1,updated_at=UTC_TIMESTAMP(6)
                        WHERE music_id=? AND youtube_video_id=? AND channel_id IS NULL AND version<9007199254740991
                        """, video.channelId(), music, video.id());
                jdbc.update("""
                        INSERT INTO music_video_candidates(id,search_id,music_id,youtube_video_id,rank_no,title,
                          channel_title,thumbnail_url,duration_millis) VALUES (?,?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID().toString(), id, music, video.id(), ++rank, video.title(),
                        video.channelTitle(), video.thumbnailUrl(), video.durationMillis());
            }
            return result(id);
        });
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
                SELECT id FROM music_video_search_results WHERE music_id=? AND metadata_fingerprint=?
                  AND policy_version=1 AND playback_country='KR' AND expires_at>UTC_TIMESTAMP(6)
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, String.class, music, fingerprint);
        return ids.isEmpty() ? null : result(ids.getFirst());
    }

    private Map<String, Object> result(String search) {
        var row = jdbc.queryForMap(
                "SELECT id search_id,music_id,result_kind,expires_at FROM music_video_search_results WHERE id=?",
                search);
        row.put("expires_at", ((LocalDateTime) row.get("expires_at")).toInstant(ZoneOffset.UTC).toString());
        var items = jdbc.queryForList(
                """
                        SELECT c.id candidate_id,c.music_id,c.youtube_video_id video_id,c.title,c.channel_title,
                        c.thumbnail_url,c.duration_millis,
                          s.channel_id,'PLAYABLE' playback_status,s.official_status FROM music_video_candidates
                          c JOIN music_video_sources s
                          ON s.music_id=c.music_id AND s.youtube_video_id=c.youtube_video_id
                        WHERE c.search_id=? ORDER BY c.rank_no
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
