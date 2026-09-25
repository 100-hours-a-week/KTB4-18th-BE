package com.muse.meomuneum.musicrecord.repository;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordDetailResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.Region;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.RegionPart;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSummary;

@Repository
public class MusicRecordRepository {
    private final JdbcTemplate jdbc;
    public MusicRecordRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public long highestMusicId() {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM music", Long.class);
        return value == null ? 0 : value;
    }

    public List<MusicItem> searchMusic(String query, long beforeId, long highWatermark, int limit) {
        String sql = "SELECT id, provider, external_music_id, title, artist_name, album_cover_url, "
                + "preview_url, youtube_video_id FROM music WHERE provider='ITUNES' AND id<? AND id<=? "
                + "AND (LOWER(title) LIKE LOWER(?) ESCAPE '!' "
                + "OR LOWER(artist_name) LIKE LOWER(?) ESCAPE '!') "
                + "ORDER BY id DESC LIMIT ?";
        String pattern = "%" + escapeLikeLiteral(query) + "%";
        return jdbc.query(sql, (row, index) -> item(row.getLong("id"), row.getString("provider"),
                row.getString("external_music_id"), row.getString("title"), row.getString("artist_name"),
                row.getString("album_cover_url"), row.getString("preview_url"),
                row.getString("youtube_video_id")), beforeId, highWatermark, pattern, pattern, limit);
    }


    public Map<String, StoredMusic> findStoredMusicByIds(List<String> externalIds, long highWatermark,
            String query) {
        if (externalIds.isEmpty()) {
            return Map.of();
        }
        String markers = String.join(",", Collections.nCopies(externalIds.size(), "?"));
        String sql = "SELECT id, provider, external_music_id, title, artist_name, album_cover_url, "
                + "preview_url, youtube_video_id, "
                + "(LOWER(title) LIKE LOWER(?) ESCAPE '!' "
                + "OR LOWER(artist_name) LIKE LOWER(?) ESCAPE '!') matches_search "
                + "FROM music WHERE provider='ITUNES' AND id<=? AND external_music_id IN (" + markers + ")";
        Object[] arguments = new Object[externalIds.size() + 3];
        String pattern = "%" + escapeLikeLiteral(query) + "%";
        arguments[0] = pattern;
        arguments[1] = pattern;
        arguments[2] = highWatermark;
        for (int i = 0; i < externalIds.size(); i++) {
            arguments[i + 3] = externalIds.get(i);
        }
        Map<String, StoredMusic> results = new LinkedHashMap<>();
        jdbc.query(sql, row -> {
            MusicItem music = item(row.getLong("id"), row.getString("provider"),
                    row.getString("external_music_id"), row.getString("title"),
                    row.getString("artist_name"), row.getString("album_cover_url"),
                    row.getString("preview_url"), row.getString("youtube_video_id"));
            results.put(music.external_music_id(), new StoredMusic(music, row.getBoolean("matches_search")));
        }, arguments);
        return results;
    }

    public record StoredMusic(MusicItem music, boolean matchesSearch) {
    }
    public Optional<Location> findNearestLocation(double latitude, double longitude) {
        String sql = "SELECT d.id dot_id, d.code dot_code, g.id sigungu_id, g.code sigungu_code, "
                + "g.name sigungu_name, s.id sido_id, s.code sido_code, s.name sido_name FROM map_dots d "
                + "JOIN regions g ON g.id=d.region_id JOIN regions s ON s.id=g.parent_id "
                + "WHERE d.is_active=TRUE AND g.is_active=TRUE AND s.is_active=TRUE "
                + "AND g.level='SIGUNGU' ORDER BY POW(d.latitude-?,2)+POW(d.longitude-?,2) LIMIT 1";
        return jdbc.query(sql, (row, index) -> location(row), latitude, longitude).stream().findFirst();
    }

    public Optional<Location> findLocation(long dotId, long sigunguId, long sidoId) {
        String sql = "SELECT d.id dot_id, d.code dot_code, g.id sigungu_id, g.code sigungu_code, "
                + "g.name sigungu_name, s.id sido_id, s.code sido_code, s.name sido_name FROM map_dots d "
                + "JOIN regions g ON g.id=d.region_id JOIN regions s ON s.id=g.parent_id "
                + "WHERE d.id=? AND g.id=? AND s.id=? AND d.is_active=TRUE "
                + "AND g.is_active=TRUE AND s.is_active=TRUE AND g.level='SIGUNGU'";
        return jdbc.query(sql, (row, index) -> location(row), dotId, sigunguId, sidoId)
                .stream().findFirst();
    }
    public long upsertMusic(MusicItem music) {
        jdbc.update("INSERT INTO music (provider, external_music_id, title, artist_name, album_cover_url, "
                        + "preview_url) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id=id",
                music.provider(), music.external_music_id(), music.title(), music.artist_name(),
                music.album_cover_url(), music.preview_url());
        Long id = jdbc.queryForObject("SELECT id FROM music WHERE provider=? AND external_music_id=?",
                Long.class, music.provider(), music.external_music_id());
        if (id == null) {
            throw new IllegalStateException("music id missing");
        }
        return id;
    }

    public Optional<MusicItem> findMusic(String provider, String externalId) {
        String sql = "SELECT id, provider, external_music_id, title, artist_name, album_cover_url, "
                + "preview_url, youtube_video_id FROM music WHERE provider=? AND external_music_id=?";
        return jdbc.query(sql, (row, index) -> item(row.getLong("id"), row.getString("provider"),
                row.getString("external_music_id"), row.getString("title"), row.getString("artist_name"),
                row.getString("album_cover_url"), row.getString("preview_url"),
                row.getString("youtube_video_id")), provider, externalId).stream().findFirst();
    }
    public long saveRecord(long userId, long musicId, Location location, String placeName,
            String memo, Instant createdAt) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO music_records (user_id,music_id,map_dot_id,region_id,"
                            + "custom_place_name,emotion_memo,created_at) VALUES (?,?,?,?,?,?,?)",
                    new String[]{"id"});
            statement.setLong(1, userId);
            statement.setLong(2, musicId);
            statement.setLong(3, location.dotId());
            statement.setLong(4, location.sigunguId());
            statement.setString(5, placeName);
            statement.setString(6, memo);
            statement.setObject(7, LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            return statement;
        }, keys);
        if (keys.getKey() == null) {
            throw new IllegalStateException("music record id missing");
        }
        return keys.getKey().longValue();
    }

    public Instant findCreatedAt(long recordId) {
        LocalDateTime value = jdbc.queryForObject("SELECT created_at FROM music_records WHERE id=?",
                (row, index) -> row.getObject("created_at", LocalDateTime.class), recordId);
        if (value == null) {
            throw new IllegalStateException("music record timestamp missing");
        }
        return value.toInstant(ZoneOffset.UTC);
    }
    public List<MusicRecordResponse> findRecords(long userId, Instant createdAt, long id, int size) {
        String sql = "SELECT mr.id record_id, mr.map_dot_id, mr.custom_place_name, mr.emotion_memo, "
                + "mr.created_at, m.id music_id, m.title, m.artist_name, m.album_cover_url, "
                + "g.id sigungu_id, g.code sigungu_code, g.name sigungu_name, "
                + "s.id sido_id, s.code sido_code, s.name sido_name "
                + "FROM music_records mr JOIN music m ON m.id=mr.music_id "
                + "JOIN regions g ON g.id=mr.region_id JOIN regions s ON s.id=g.parent_id "
                + "JOIN map_dots d ON d.id=mr.map_dot_id "
                + "WHERE mr.user_id=? AND mr.deleted_at IS NULL "
                + "AND (? IS NULL OR mr.created_at < ? OR (mr.created_at=? AND mr.id<?)) "
                + "ORDER BY mr.created_at DESC,mr.id DESC LIMIT ?";
        LocalDateTime timestamp = createdAt == null ? null : LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        return jdbc.query(sql, (row, index) -> new MusicRecordResponse(
                row.getLong("record_id"), summary(row), row.getLong("map_dot_id"), region(row),
                row.getString("custom_place_name"), row.getString("emotion_memo"),
                row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                userId, timestamp, timestamp, timestamp, id, size);
    }
    public Optional<MusicRecordDetailResponse> findRecord(long userId, long recordId) {
        String sql = "SELECT mr.id record_id, mr.map_dot_id, mr.custom_place_name, mr.emotion_memo, "
                + "mr.created_at, mr.updated_at, m.id music_id, "
                + "m.title, m.artist_name, m.album_cover_url, "
                + "g.id sigungu_id, g.code sigungu_code, g.name sigungu_name, "
                + "s.id sido_id, s.code sido_code, s.name sido_name "
                + "FROM music_records mr JOIN music m ON m.id=mr.music_id "
                + "JOIN regions g ON g.id=mr.region_id JOIN regions s ON s.id=g.parent_id "
                + "JOIN map_dots d ON d.id=mr.map_dot_id "
                + "WHERE mr.id=? AND mr.user_id=? AND mr.deleted_at IS NULL";
        return jdbc.query(sql, (row, index) -> new MusicRecordDetailResponse(
                row.getLong("record_id"), summary(row), row.getLong("map_dot_id"), region(row),
                row.getString("custom_place_name"), row.getString("emotion_memo"),
                row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getObject("updated_at", LocalDateTime.class) == null ? null
                        : row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                recordId, userId).stream().findFirst();
    }
    public boolean existsActiveRecord(long recordId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM music_records WHERE id=? AND deleted_at IS NULL", Integer.class, recordId);
        return count != null && count > 0;
    }
    public int updateRecord(long userId, long recordId, String placeName, String memo, Instant updatedAt) {
        return jdbc.update("UPDATE music_records SET custom_place_name=?, emotion_memo=?, updated_at=? "
                + "WHERE id=? AND user_id=? AND deleted_at IS NULL",
                placeName, memo, LocalDateTime.ofInstant(updatedAt, ZoneOffset.UTC), recordId, userId);
    }
    private MusicItem item(long id, String provider, String externalId, String title, String artist,
            String cover, String preview, String youtubeVideoId) {
        return new MusicItem(id, provider, externalId, title, artist, cover, preview,
                youtubeVideoId, youtubeVideoId != null && youtubeVideoId.matches("[A-Za-z0-9_-]{11}"));
    }

    private String escapeLikeLiteral(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private MusicSummary summary(java.sql.ResultSet row) throws java.sql.SQLException {
        return new MusicSummary(row.getLong("music_id"), row.getString("title"),
                row.getString("artist_name"), row.getString("album_cover_url"));
    }

    private Region region(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Region(new RegionPart(row.getLong("sido_id"), row.getString("sido_code"),
                row.getString("sido_name")), new RegionPart(row.getLong("sigungu_id"),
                row.getString("sigungu_code"), row.getString("sigungu_name")));
    }

    private Location location(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Location(row.getLong("dot_id"), row.getString("dot_code"), row.getLong("sigungu_id"),
                row.getString("sigungu_code"), row.getString("sigungu_name"), row.getLong("sido_id"),
                row.getString("sido_code"), row.getString("sido_name"));
    }

    public record Location(long dotId, String dotCode, long sigunguId, String sigunguCode,
            String sigunguName, long sidoId, String sidoCode, String sidoName) {
        public Region region() {
            return new Region(new RegionPart(sidoId, sidoCode, sidoName),
                    new RegionPart(sigunguId, sigunguCode, sigunguName));
        }
    }
}
