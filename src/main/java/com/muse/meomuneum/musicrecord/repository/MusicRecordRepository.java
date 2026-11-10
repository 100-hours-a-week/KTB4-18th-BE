package com.muse.meomuneum.musicrecord.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.music.domain.MusicMetadataPolicy;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordDetailResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicRecordResponse;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSummary;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.Region;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.RegionPart;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames;

@Repository
public class MusicRecordRepository {
    private static final List<String> SEARCH_NAME_COLUMNS = List.of("title", "artist_name", "title_us",
            "artist_name_us", "title_ko", "artist_name_ko");
    private static final String MUSIC_NAMES = "COALESCE(title_ko, title_us, title) title, "
            + "COALESCE(artist_name_ko, artist_name_us, artist_name) artist_name, ";
    private static final String RECORD_MUSIC_NAMES = "COALESCE(m.title_ko, m.title_us, m.title) title, "
            + "COALESCE(m.artist_name_ko, m.artist_name_us, m.artist_name) artist_name, ";
    private static final String STOREFRONT_COLUMNS = "title_us, artist_name_us, title_ko, artist_name_ko";
    private static final String PLAYBACK_VERIFIED = "EXISTS(SELECT 1 FROM music_playback_sources ps " +
            "WHERE ps.music_id=music.id AND BINARY ps.youtube_video_id=BINARY music.youtube_video_id " +
            "AND ps.status='VERIFIED' AND ps.playback_country='KR' " +
            "AND ps.verified_at<=UTC_TIMESTAMP(6) AND ps.valid_until>UTC_TIMESTAMP(6)) playback_verified";
    private final JdbcTemplate jdbc;
    public MusicRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    public long highestMusicId() {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM music", Long.class);
        return value == null ? 0 : value;
    }

    public List<MusicItem> searchMusic(String query, long beforeId, long highWatermark, int limit) {
        boolean firstPage = beforeId == Long.MAX_VALUE;
        return searchMusic(query, highWatermark, limit, "id<?", List.of(beforeId),
                firstPage ? "match_priority, release_epoch_second DESC, id DESC" : "id DESC", firstPage);
    }

    public List<MusicItem> searchMusic(String query, long beforeId, Long beforeRelease, long highWatermark,
            int limit) {
        return searchMusic(query, beforeId, beforeRelease, null, highWatermark, limit);
    }

    public List<MusicItem> searchMusic(String query, long beforeId, Long beforeRelease, Integer beforePriority,
            long highWatermark, int limit) {
        String boundary = beforeRelease == null
                ? "release_epoch_second IS NULL AND id<?"
                : "(release_epoch_second < ? OR (release_epoch_second = ? AND id<?) OR release_epoch_second IS NULL)";
        var anchor = new ArrayList<Long>();
        boolean ranked = beforePriority != null;
        if (ranked) {
            boundary = "(match_priority > ? OR (match_priority = ? AND " + boundary + "))";
            anchor.add(beforePriority.longValue());
            anchor.add(beforePriority.longValue());
        }
        if (beforeRelease != null) {
            anchor.add(beforeRelease);
            anchor.add(beforeRelease);
        }
        anchor.add(beforeId);
        String order = ranked
                ? "match_priority, release_epoch_second DESC, id DESC"
                : "release_epoch_second DESC, id DESC";
        return searchMusic(query, highWatermark, limit, boundary, anchor, order, ranked);
    }

    private List<MusicItem> searchMusic(String query, long highWatermark, int limit, String boundary,
            List<Long> anchor, String order, boolean ranked) {
        String namesMatch = compactNamesMatch("");
        String literal = escapeLikeLiteral(query.replace(" ", ""));
        String compact = "%" + literal + "%";
        var arguments = new ArrayList<Object>();
        String rankSelect = "";
        if (ranked) {
            String rankMatch = compactNamesMatch(" COLLATE utf8mb4_0900_as_cs");
            rankSelect = ", CASE WHEN " + rankMatch + " THEN 0 WHEN " + rankMatch + " THEN 1 ELSE 2 END match_priority";
            arguments.addAll(Collections.nCopies(SEARCH_NAME_COLUMNS.size(), literal));
            arguments.addAll(Collections.nCopies(SEARCH_NAME_COLUMNS.size(), compact));
        }
        arguments.add(highWatermark);
        arguments.addAll(Collections.nCopies(SEARCH_NAME_COLUMNS.size(), compact));
        StringBuilder conditions = new StringBuilder(namesMatch).append(" OR (");
        String[] tokens = query.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                conditions.append(" AND ");
            }
            conditions.append(namesMatch);
            String pattern = "%" + escapeLikeLiteral(tokens[i]) + "%";
            arguments.addAll(Collections.nCopies(SEARCH_NAME_COLUMNS.size(), pattern));
        }
        conditions.append(")");
        arguments.addAll(anchor);
        arguments.add(limit);
        String sql = "SELECT id, provider, external_music_id, " + MUSIC_NAMES + "album_cover_url, "
                + "preview_url, youtube_video_id, release_epoch_second, " + PLAYBACK_VERIFIED + rankSelect
                + " FROM music WHERE provider='ITUNES' AND id<=? AND (" + conditions + ")";
        if (ranked) {
            sql = "SELECT * FROM (" + sql + ") ranked WHERE " + boundary;
        } else {
            sql += " AND " + boundary;
        }
        sql += " ORDER BY " + order + " LIMIT ?";
        return jdbc.query(sql, (row, index) -> catalogItem(row, ranked ? row.getInt("match_priority") : null),
                arguments.toArray());
    }

    private String compactNamesMatch(String collation) {
        return SEARCH_NAME_COLUMNS.stream()
                .map(column -> "LOWER(REGEXP_REPLACE(" + column + ", '[[:space:]]', ''))" + collation
                        + " LIKE LOWER(?) ESCAPE '!'")
                .collect(Collectors.joining(" OR ", "(", ")"));
    }

    public Map<String, StoredMusic> findStoredMusicByIds(List<String> externalIds, long highWatermark,
            String query) {
        if (externalIds.isEmpty()) {
            return Map.of();
        }
        String markers = String.join(",", Collections.nCopies(externalIds.size(), "?"));
        String namesMatch = SEARCH_NAME_COLUMNS.stream().map(column -> "LOWER(" + column + ") LIKE LOWER(?) ESCAPE '!'")
                .collect(Collectors.joining(" OR ", "(", ")"));
        String sql = "SELECT id, provider, external_music_id, " + MUSIC_NAMES + "album_cover_url, "
                + "preview_url, youtube_video_id, release_epoch_second, " + PLAYBACK_VERIFIED + ", " + namesMatch
                + " IS TRUE matches_search "
                + "FROM music WHERE provider='ITUNES' AND id<=? AND external_music_id IN (" + markers + ")";
        String pattern = "%" + escapeLikeLiteral(query) + "%";
        var arguments = new java.util.ArrayList<Object>(Collections.nCopies(SEARCH_NAME_COLUMNS.size(), pattern));
        arguments.add(highWatermark);
        arguments.addAll(externalIds);
        Map<String, StoredMusic> results = new LinkedHashMap<>();
        jdbc.query(sql, row -> {
            MusicItem music = catalogItem(row);
            results.put(music.external_music_id(), new StoredMusic(music, row.getBoolean("matches_search")));
        }, arguments.toArray());
        return results;
    }

    public record StoredMusic(MusicItem music, boolean matchesSearch) {
    }

    public record MusicIdentity(String provider, String externalId) {
    }
    public Optional<Location> findLocation(long dotId, long sigunguId, long sidoId) {
        String sql = "SELECT d.id dot_id, d.code dot_code, g.id sigungu_id, g.code sigungu_code, "
                + "g.name sigungu_name, s.id sido_id, s.code sido_code, s.name sido_name FROM map_dots d "
                + "JOIN regions g ON g.id=? JOIN regions s ON s.id=g.parent_id "
                + "WHERE d.id=? AND g.id=? AND s.id=? AND d.is_active=TRUE "
                + "AND g.is_active=TRUE AND s.is_active=TRUE AND g.level='SIGUNGU'";
        return jdbc.query(sql, (row, index) -> location(row), sigunguId, dotId, sigunguId, sidoId)
                .stream().findFirst();
    }
    public void insertMusicIfAbsent(MusicItem music) {
        MusicMetadataPolicy.validate(music.title(), music.artist_name());
        jdbc.update("INSERT INTO music (provider, external_music_id, title, artist_name, album_cover_url, "
                + "preview_url, youtube_video_id, " + STOREFRONT_COLUMNS + ", release_epoch_second) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id=id",
                music.provider(), music.external_music_id(), music.title(), music.artist_name(),
                music.album_cover_url(), music.preview_url(), music.youtube_video_id(),
                music.storefrontNames().titleUs(), music.storefrontNames().artistNameUs(),
                music.storefrontNames().titleKo(), music.storefrontNames().artistNameKo(), music.releaseEpochSecond());
    }

    public MusicItem insertSelectedMusic(MusicItem music) {
        MusicMetadataPolicy.validate(music.title(), music.artist_name());
        var keys = new GeneratedKeyHolder();
        var names = music.storefrontNames();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO music (provider, external_music_id, title, artist_name, album_cover_url, "
                            + "preview_url, youtube_video_id, " + STOREFRONT_COLUMNS + ", release_epoch_second) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            statement.setString(1, music.provider());
            statement.setString(2, music.external_music_id());
            statement.setString(3, music.title());
            statement.setString(4, music.artist_name());
            statement.setString(5, music.album_cover_url());
            statement.setString(6, music.preview_url());
            statement.setString(7, music.youtube_video_id());
            statement.setString(8, names.titleUs());
            statement.setString(9, names.artistNameUs());
            statement.setString(10, names.titleKo());
            statement.setString(11, names.artistNameKo());
            statement.setObject(12, music.releaseEpochSecond(), java.sql.Types.BIGINT);
            return statement;
        }, keys);
        if (keys.getKey() == null) {
            throw new IllegalStateException("music id missing");
        }
        return item(keys.getKey().longValue(), music.provider(), music.external_music_id(), music.title(),
                music.artist_name(), music.album_cover_url(), music.preview_url(), music.youtube_video_id(),
                false, music.releaseEpochSecond(), null);
    }

    public long upsertMusic(MusicItem music) {
        MusicMetadataPolicy.validate(music.title(), music.artist_name());
        var names = music.storefrontNames();
        jdbc.update("INSERT INTO music (provider, external_music_id, title, artist_name, album_cover_url, "
                + "preview_url, " + STOREFRONT_COLUMNS + ", release_epoch_second) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS incoming "
                + "ON DUPLICATE KEY UPDATE "
                + "title=COALESCE(incoming.title_ko, music.title_ko, incoming.title), "
                + "artist_name=COALESCE(incoming.artist_name_ko, music.artist_name_ko, incoming.artist_name), "
                + "title_us=COALESCE(incoming.title_us, music.title_us), "
                + "artist_name_us=COALESCE(incoming.artist_name_us, music.artist_name_us), "
                + "title_ko=COALESCE(incoming.title_ko, music.title_ko), "
                + "artist_name_ko=COALESCE(incoming.artist_name_ko, music.artist_name_ko), "
                + "album_cover_url=COALESCE(incoming.album_cover_url, music.album_cover_url), "
                + "preview_url=COALESCE(incoming.preview_url, music.preview_url), updated_at=CURRENT_TIMESTAMP(6)",
                music.provider(), music.external_music_id(), music.title(), music.artist_name(),
                music.album_cover_url(), music.preview_url(), names.titleUs(), names.artistNameUs(),
                names.titleKo(), names.artistNameKo(), music.releaseEpochSecond());
        Long id = jdbc.queryForObject("SELECT id FROM music WHERE provider=? AND external_music_id=?",
                Long.class, music.provider(), music.external_music_id());
        if (id == null) {
            throw new IllegalStateException("music id missing");
        }
        return id;
    }

    public void mergeStorefrontNames(MusicItem music) {
        var names = music.storefrontNames();
        if (names.titleUs() == null && names.artistNameUs() == null
                && names.titleKo() == null && names.artistNameKo() == null) {
            return;
        }
        jdbc.update("UPDATE music SET title_us=COALESCE(?, title_us), artist_name_us=COALESCE(?, artist_name_us), "
                + "title_ko=COALESCE(?, title_ko), artist_name_ko=COALESCE(?, artist_name_ko) "
                + "WHERE provider=? AND external_music_id=?",
                names.titleUs(), names.artistNameUs(), names.titleKo(), names.artistNameKo(),
                music.provider(), music.external_music_id());
    }

    public Optional<MusicItem> findMusic(String provider, String externalId) {
        String sql = "SELECT id, provider, external_music_id, " + MUSIC_NAMES + "album_cover_url, "
                + "preview_url, youtube_video_id, release_epoch_second, " + PLAYBACK_VERIFIED
                + " FROM music WHERE provider=? "
                + "AND external_music_id=?";
        return jdbc.query(sql, (row, index) -> catalogItem(row), provider, externalId).stream().findFirst();
    }

    public Optional<MusicIdentity> findRecordMusicIdentity(long userId, long recordId) {
        return jdbc.query("SELECT m.provider, m.external_music_id FROM music_records mr "
                + "JOIN music m ON m.id=mr.music_id "
                + "WHERE mr.id=? AND mr.user_id=? AND mr.deleted_at IS NULL",
                (row, index) -> new MusicIdentity(row.getString("provider"),
                        row.getString("external_music_id")),
                recordId, userId).stream().findFirst();
    }

    public int updateRecordMusic(long userId, long recordId, long musicId, Instant updatedAt) {
        return jdbc.update("UPDATE music_records SET music_id=?, updated_at=? "
                + "WHERE id=? AND user_id=? AND deleted_at IS NULL",
                musicId, LocalDateTime.ofInstant(updatedAt, ZoneOffset.UTC), recordId, userId);
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
                + "mr.created_at, m.id music_id, " + RECORD_MUSIC_NAMES + "m.album_cover_url, "
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
                + RECORD_MUSIC_NAMES + "m.album_cover_url, "
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
                row.getObject("updated_at", LocalDateTime.class) == null
                        ? null
                        : row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                recordId, userId).stream().findFirst();
    }
    public boolean existsActiveRecord(long recordId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM music_records WHERE id=? AND deleted_at IS NULL", Integer.class, recordId);
        return count != null && count > 0;
    }

    public boolean existsActiveRecordInRegion(long userId, long sigunguId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM music_records mr "
                + "JOIN regions r ON r.id=mr.region_id WHERE mr.user_id=? AND mr.region_id=? "
                + "AND r.level='SIGUNGU' AND r.is_active=TRUE AND mr.deleted_at IS NULL LIMIT 1)",
                Boolean.class, userId, sigunguId);
        return Boolean.TRUE.equals(exists);
    }
    public int updateRecord(long userId, long recordId, String placeName, String memo, Instant updatedAt) {
        return jdbc.update("UPDATE music_records SET custom_place_name=?, emotion_memo=?, updated_at=? "
                + "WHERE id=? AND user_id=? AND deleted_at IS NULL",
                placeName, memo, LocalDateTime.ofInstant(updatedAt, ZoneOffset.UTC), recordId, userId);
    }

    public int deleteRecord(long userId, long recordId, Instant deletedAt) {
        return jdbc.update("UPDATE music_records SET deleted_at=? "
                + "WHERE id=? AND user_id=? AND deleted_at IS NULL",
                LocalDateTime.ofInstant(deletedAt, ZoneOffset.UTC), recordId, userId);
    }
    private MusicItem catalogItem(ResultSet row) throws SQLException {
        return catalogItem(row, null);
    }

    private MusicItem catalogItem(ResultSet row, Integer matchPriority) throws SQLException {
        return item(row.getLong("id"), row.getString("provider"), row.getString("external_music_id"),
                row.getString("title"), row.getString("artist_name"), row.getString("album_cover_url"),
                row.getString("preview_url"), row.getString("youtube_video_id"),
                row.getBoolean("playback_verified"), row.getObject("release_epoch_second", Long.class), matchPriority);
    }

    private MusicItem item(long id, String provider, String externalId, String title, String artist,
            String cover, String preview, String youtubeVideoId, boolean playbackVerified,
            Long releaseEpochSecond, Integer matchPriority) {
        return new MusicItem(id, provider, externalId, title, artist, cover, preview, youtubeVideoId,
                playbackVerified, StorefrontNames.EMPTY,
                releaseEpochSecond, matchPriority);
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
                row.getString("sido_name")),
                new RegionPart(row.getLong("sigungu_id"),
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
