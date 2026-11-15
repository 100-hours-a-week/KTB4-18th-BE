package com.muse.meomuneum.musicrecord.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;

@Repository
public class MusicSearchIngestJobRepository {
    private static final String COLUMNS = "id, provider, external_music_id, title, artist_name, album_cover_url, "
            + "preview_url, youtube_video_id, status, attempt_count, lease_token, payload_version, "
            + "metadata_observed_at, attempt_started_at, lease_until, title_us, artist_name_us, title_ko, artist_name_ko, release_epoch_second";
    private final JdbcTemplate jdbc;

    public MusicSearchIngestJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void admit(List<MusicItem> items) {
        LocalDateTime observed = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        items.stream().sorted(Comparator.comparing(MusicItem::provider)
                .thenComparing(MusicItem::external_music_id)).forEach(item -> admit(item, observed));
    }

    public void admit(MusicItem item, LocalDateTime observed) {
        jdbc.update("INSERT INTO music_search_ingest_jobs (provider, external_music_id, title, artist_name, "
                + "album_cover_url, preview_url, youtube_video_id, status, attempt_count, next_attempt_at, "
                + "metadata_observed_at, payload_version, title_us, artist_name_us, title_ko, artist_name_ko, release_epoch_second) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED', 0, "
                + "CURRENT_TIMESTAMP(6), ?, 1, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id=id", item.provider(),
                item.external_music_id(), item.title(), item.artist_name(), item.album_cover_url(),
                item.preview_url(), item.youtube_video_id(), observed, item.storefrontNames().titleUs(),
                item.storefrontNames().artistNameUs(), item.storefrontNames().titleKo(),
                item.storefrontNames().artistNameKo(), item.releaseEpochSecond());
        JobState state = jdbc.query("SELECT " + COLUMNS + " FROM music_search_ingest_jobs "
                + "WHERE provider=? AND external_music_id=? FOR UPDATE", (row, index) -> state(row),
                item.provider(), item.external_music_id()).getFirst();
        if (!observed.isAfter(state.job().observedAt())) {
            return;
        }
        item = merge(item, state.job().music());
        if (samePayload(item, state.job().music())) {
            jdbc.update("UPDATE music_search_ingest_jobs SET metadata_observed_at=? WHERE id=?",
                    observed, state.job().id());
            return;
        }
        jdbc.update("UPDATE music_search_ingest_jobs SET title=?, artist_name=?, album_cover_url=?, "
                + "preview_url=?, youtube_video_id=?, title_us=?, artist_name_us=?, title_ko=?, artist_name_ko=?, release_epoch_second=?, metadata_observed_at=?, payload_version=payload_version+1, "
                + "status=IF(status='DEAD','DEAD','QUEUED'), "
                + "attempt_count=IF(status='DEAD',attempt_count,0), lease_token=NULL, lease_until=NULL, "
                + "attempt_started_at=NULL, next_attempt_at=CURRENT_TIMESTAMP(6), "
                + "last_error_code=IF(status='DEAD',last_error_code,NULL), updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                item.title(), item.artist_name(), item.album_cover_url(), item.preview_url(), item.youtube_video_id(),
                item.storefrontNames().titleUs(), item.storefrontNames().artistNameUs(),
                item.storefrontNames().titleKo(), item.storefrontNames().artistNameKo(), item.releaseEpochSecond(),
                observed, state.job().id());
    }

    private MusicItem merge(MusicItem incoming, MusicItem previous) {
        var a = incoming.storefrontNames();
        var b = previous.storefrontNames();
        return new MusicItem(null, incoming.provider(), incoming.external_music_id(),
                keep(keep(a.titleKo(), b.titleKo()), incoming.title()),
                keep(keep(a.artistNameKo(), b.artistNameKo()), incoming.artist_name()),
                keep(incoming.album_cover_url(), previous.album_cover_url()),
                keep(incoming.preview_url(), previous.preview_url()),
                keep(incoming.youtube_video_id(), previous.youtube_video_id()), false,
                new StorefrontNames(keep(a.titleUs(), b.titleUs()), keep(a.artistNameUs(), b.artistNameUs()),
                        keep(a.titleKo(), b.titleKo()), keep(a.artistNameKo(), b.artistNameKo()), false),
                incoming.releaseEpochSecond() == null ? previous.releaseEpochSecond() : incoming.releaseEpochSecond());
    }

    private String keep(String incoming, String previous) {
        return incoming == null ? previous : incoming;
    }

    private boolean samePayload(MusicItem first, MusicItem second) {
        return Objects.equals(first.title(), second.title())
                && Objects.equals(first.artist_name(), second.artist_name())
                && Objects.equals(first.album_cover_url(), second.album_cover_url())
                && Objects.equals(first.preview_url(), second.preview_url())
                && Objects.equals(first.youtube_video_id(), second.youtube_video_id())
                && Objects.equals(first.storefrontNames(), second.storefrontNames())
                && Objects.equals(first.releaseEpochSecond(), second.releaseEpochSecond());
    }

    public List<JobState> findDue(int limit, int maxAttempts) {
        return jdbc.query("SELECT " + COLUMNS + " FROM music_search_ingest_jobs "
                + "WHERE status='QUEUED' AND attempt_count<? AND next_attempt_at<=CURRENT_TIMESTAMP(6) "
                + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED", (row, index) -> state(row), maxAttempts, limit);
    }

    public List<JobState> findRecoverable(int limit, int maxAttempts) {
        return jdbc.query("SELECT " + COLUMNS + " FROM music_search_ingest_jobs "
                + "WHERE (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)) "
                + "OR (status='QUEUED' AND attempt_count>=?) "
                + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED", (row, index) -> state(row), maxAttempts, limit);
    }

    public void claim(long id, String token, int leaseSeconds) {
        jdbc.update("UPDATE music_search_ingest_jobs SET status='PROCESSING', lease_token=?, "
                + "lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? SECOND), "
                + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", token, leaseSeconds, id);
    }

    public Optional<JobState> lock(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM music_search_ingest_jobs WHERE id=? FOR UPDATE",
                (row, index) -> state(row), id).stream().findFirst();
    }

    public boolean active(ClaimedJob job, boolean requireLease) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_jobs "
                + "WHERE id=? AND status='PROCESSING' AND lease_token=? AND payload_version=?"
                + (requireLease ? " AND lease_until>CURRENT_TIMESTAMP(6)" : ""), Integer.class,
                job.id(), job.leaseToken(), job.payloadVersion());
        return count != null && count == 1;
    }

    public void start(ClaimedJob job, int leaseSeconds) {
        jdbc.update("UPDATE music_search_ingest_jobs SET attempt_count=attempt_count+1, "
                + "attempt_started_at=CURRENT_TIMESTAMP(6), "
                + "lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? SECOND), "
                + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", leaseSeconds, job.id());
    }

    public void done(long id) {
        jdbc.update("UPDATE music_search_ingest_jobs SET status='DONE', lease_token=NULL, lease_until=NULL, "
                + "attempt_started_at=NULL, last_error_code=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
    }

    public void retry(long id, long delayMillis, String errorCode) {
        jdbc.update("UPDATE music_search_ingest_jobs SET status='QUEUED', lease_token=NULL, lease_until=NULL, "
                + "attempt_started_at=NULL, next_attempt_at=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)), "
                + "last_error_code=?, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", delayMillis * 1000, errorCode, id);
    }

    public void dead(long id, String errorCode) {
        jdbc.update("UPDATE music_search_ingest_jobs SET status='DEAD', lease_token=NULL, lease_until=NULL, "
                + "attempt_started_at=NULL, last_error_code=?, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", errorCode,
                id);
    }

    public void replay(long id) {
        jdbc.update("UPDATE music_search_ingest_jobs SET status='QUEUED', attempt_count=0, "
                + "next_attempt_at=CURRENT_TIMESTAMP(6), lease_token=NULL, lease_until=NULL, "
                + "attempt_started_at=NULL, last_error_code=NULL, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
    }

    private JobState state(ResultSet row) throws SQLException {
        String youtube = row.getString("youtube_video_id");
        var item = new MusicItem(null, row.getString("provider"), row.getString("external_music_id"),
                row.getString("title"), row.getString("artist_name"), row.getString("album_cover_url"),
                row.getString("preview_url"), youtube, false,
                new StorefrontNames(row.getString("title_us"), row.getString("artist_name_us"),
                        row.getString("title_ko"), row.getString("artist_name_ko"), false),
                row.getObject("release_epoch_second", Long.class));
        var job = new ClaimedJob(row.getLong("id"), item, row.getInt("attempt_count"), row.getString("lease_token"),
                row.getLong("payload_version"), row.getObject("metadata_observed_at", LocalDateTime.class));
        return new JobState(job, row.getString("status"), row.getObject("attempt_started_at", LocalDateTime.class));
    }

    public record JobState(ClaimedJob job, String status, LocalDateTime attemptStartedAt) {
    }
}
