package com.muse.meomuneum.music.catalog;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Region provenance is independent of video availability; legacy ADD still requires a reviewed source. */
@Repository
public class RequestableMusicRepository {
    private final JdbcTemplate jdbc;

    public RequestableMusicRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String ELIGIBILITY = """
            SELECT m.id music_id,
              COALESCE(NULLIF(TRIM(m.title_ko),''),NULLIF(TRIM(m.title_us),''),m.title) title,
              COALESCE(NULLIF(TRIM(m.artist_name_ko),''),NULLIF(TRIM(m.artist_name_us),''),m.artist_name) artist_name,
              m.album_cover_url,ps.youtube_video_id,ps.duration_millis,
              (ps.music_id IS NOT NULL) is_queueable,
              EXISTS(SELECT 1 FROM music_records mr
                WHERE mr.music_id=m.id AND mr.region_id=? AND mr.deleted_at IS NULL
                  AND mr.user_id=?) recorded,
              EXISTS(SELECT 1 FROM recommendation_items i JOIN recommendation_sessions s
                ON s.id=i.recommendation_session_id WHERE i.music_id=m.id AND s.region_id=?
                  AND s.user_id=? AND s.status='COMPLETED'
                  AND ((s.trigger_type='CHATBOT' AND s.input_type IN ('TEXT','VOICE','IMAGE'))
                    OR (s.trigger_type='LOCATION' AND s.input_type='SYSTEM'))) recommended
            FROM music m LEFT JOIN music_playback_sources ps ON ps.music_id=m.id
              AND BINARY ps.youtube_video_id=BINARY m.youtube_video_id AND ps.status='VERIFIED'
              AND ps.playback_country='KR' AND ps.verified_at<=UTC_TIMESTAMP(6)
              AND ps.valid_until>UTC_TIMESTAMP(6)
            JOIN regions g ON g.id=? JOIN regions p ON p.id=g.parent_id
            JOIN users requester ON requester.id=? AND requester.deleted_at IS NULL
            WHERE g.level='SIGUNGU' AND p.level='SIDO'
              AND g.is_active=TRUE AND p.is_active=TRUE

            """;

    public Optional<Sources> findSources(long userId, long regionId, long musicId) {
        return findSources(userId, regionId, musicId, true);
    }

    public Optional<Sources> findProvenance(long userId, long regionId, long musicId) {
        return findSources(userId, regionId, musicId, false);
    }

    private Optional<Sources> findSources(long userId, long regionId, long musicId, boolean reviewed) {
        return jdbc.query(ELIGIBILITY + " AND m.id=?" + (reviewed ? " AND ps.music_id IS NOT NULL" : ""),
                (row, index) -> new Sources(row.getLong("music_id"),
                        row.getBoolean("recorded"), row.getBoolean("recommended")),
                regionId, userId, regionId, userId, regionId, userId, musicId)
                .stream().filter(value -> value.recorded() || value.recommended()).findFirst();
    }

    public List<Map<String, Object>> search(long userId, long regionId, String query,
            long after, int limit) {
        var arguments = new ArrayList<Object>(List.of(regionId, userId, regionId, userId, regionId, userId, after));
        String compact = normalizeQuery(query).replace(" ", "");
        var columns = List.of("m.title", "m.artist_name", "m.title_ko", "m.artist_name_ko",
                "m.title_us", "m.artist_name_us");
        String matches = columns.stream().map(column -> {
            arguments.add(compact);
            return "LOCATE(?,LOWER(REGEXP_REPLACE(COALESCE(" + column
                    + ",''),'[[:space:]]','')) COLLATE utf8mb4_0900_ai_ci)>0";
        }).collect(Collectors.joining(" OR "));
        arguments.add(limit);
        var rows = jdbc.queryForList("SELECT * FROM (" + ELIGIBILITY + " AND m.id>? AND (" + matches
                + ")) eligible WHERE recorded=TRUE OR recommended=TRUE ORDER BY music_id LIMIT ?",
                arguments.toArray());
        for (var row : rows) {
            Object queueable = row.get("is_queueable");
            row.put("is_queueable", Boolean.TRUE.equals(queueable)
                    || queueable instanceof Number number && number.intValue() != 0);
            List<String> sources = new ArrayList<>();
            if (((Number) row.remove("recorded")).intValue() != 0) {
                sources.add("RECORDED");
            }
            if (((Number) row.remove("recommended")).intValue() != 0) {
                sources.add("RECOMMENDED");
            }
            row.put("sources", sources);
        }
        return rows;
    }

    public static String normalizeQuery(String query) {
        return Normalizer.normalize(query == null ? "" : query, Normalizer.Form.NFC)
                .replaceAll("[\\p{javaWhitespace}\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    public record Sources(long musicId, boolean recorded, boolean recommended) {
    }
}
