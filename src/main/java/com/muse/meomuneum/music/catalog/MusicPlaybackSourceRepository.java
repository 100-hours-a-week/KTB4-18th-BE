package com.muse.meomuneum.music.catalog;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator writes; never component-scanned as a public administration API. */
public final class MusicPlaybackSourceRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MusicPlaybackSourceRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(5);
    }

    public void save(Review review, YouTubeVideoVerifier.Video video, Instant verifiedAt) {
        review.validate();
        if (!review.videoId().equals(video.id()) || !review.channelId().equals(video.channelId())
                || video.durationMillis() <= 0) {
            throw new IllegalArgumentException("video does not match reviewed source");
        }
        transactions.executeWithoutResult(tx -> {
            lockAndCheck(review.musicId(), review.expectedVersion());
            jdbc.update("""
                    INSERT INTO music_playback_sources
                    (music_id,youtube_video_id,channel_id,channel_kind,review_reference,reviewed_by,status,
                     duration_millis,playback_country,verified_at,valid_until,version)
                    VALUES (?,?,?,?,?,?,'VERIFIED',?,'KR',?,?,?)
                    ON DUPLICATE KEY UPDATE youtube_video_id=VALUES(youtube_video_id),channel_id=VALUES(channel_id),
                    channel_kind=VALUES(channel_kind),review_reference=VALUES(review_reference),
                    reviewed_by=VALUES(reviewed_by),status='VERIFIED',duration_millis=VALUES(duration_millis),
                    playback_country='KR',verified_at=VALUES(verified_at),valid_until=VALUES(valid_until),
                    version=VALUES(version)
                    """, review.musicId(), video.id(), video.channelId(), review.channelKind(), review.evidence(),
                    review.reviewer(), video.durationMillis(), at(verifiedAt),
                    at(verifiedAt.plusSeconds(review.validDays() * 86400L)), review.expectedVersion() + 1);
            jdbc.update("UPDATE music SET youtube_video_id=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                    video.id(), review.musicId());
        });
    }

    public void disable(long musicId, long expectedVersion) {
        if (musicId <= 0 || expectedVersion <= 0) {
            throw new IllegalArgumentException("existing source version required");
        }
        transactions.executeWithoutResult(tx -> {
            lockAndCheck(musicId, expectedVersion);
            jdbc.update("UPDATE music_playback_sources SET status='UNAVAILABLE',version=version+1 WHERE music_id=?",
                    musicId);
        });
    }

    private void lockAndCheck(long musicId, long expectedVersion) {
        var music = jdbc.queryForList("SELECT id FROM music WHERE id=? FOR UPDATE", musicId);
        if (music.isEmpty()) {
            throw new IllegalArgumentException("music not found");
        }
        var versions = jdbc.queryForList("SELECT version FROM music_playback_sources WHERE music_id=?", Long.class,
                musicId);
        long version = versions.isEmpty() ? 0 : versions.getFirst();
        if (version != expectedVersion) {
            throw new IllegalStateException("source version conflict");
        }
    }

    private LocalDateTime at(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record Review(long musicId, String videoId, String channelId, String channelKind, String evidence,
            String reviewer, int validDays, long expectedVersion) {
        public void validate() {
            if (musicId <= 0 || expectedVersion < 0 || expectedVersion == Long.MAX_VALUE
                    || !Objects.toString(videoId, "").matches("[A-Za-z0-9_-]{11}")
                    || !Objects.toString(channelId, "").matches("UC[A-Za-z0-9_-]{22}")
                    || !("ARTIST".equals(channelKind) || "DISTRIBUTOR".equals(channelKind)
                            || "TOPIC".equals(channelKind))
                    || evidence == null || evidence.isBlank() || evidence.length() > 1000
                    || reviewer == null || reviewer.isBlank() || reviewer.length() > 100
                    || validDays < 1 || validDays > 30) {
                throw new IllegalArgumentException("invalid review manifest");
            }
        }
    }
}
