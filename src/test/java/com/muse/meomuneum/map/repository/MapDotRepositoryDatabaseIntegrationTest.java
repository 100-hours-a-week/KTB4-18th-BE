package com.muse.meomuneum.map.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.map.repository.MapDotRepository.LatestMapDotRecord;

@SpringBootTest(properties = "auth.jwt.secret=development-only-secret-with-at-least-32-bytes")
@ActiveProfiles("test")
@Transactional
class MapDotRepositoryDatabaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MapDotRepository repository;

    @Test
    void choosesLatestEligibleRecordPerDotAndExcludesPrivateDeletedAndWithdrawnRows() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long sidoId = insertRegion("s" + suffix, "테스트 시도", "SIDO", null);
        long sigunguId = insertRegion("g" + suffix, "테스트 시군구", "SIGUNGU", sidoId);

        long publicUser = insertUser(suffix + "p", null);
        long privateUser = insertUser(suffix + "v", null);
        long withdrawnUser = insertUser(suffix + "w", LocalDateTime.parse("2026-09-01T00:00:00"));
        long noSettingsUser = insertUser(suffix + "n", null);
        setVisibility(publicUser, "PUBLIC");
        setVisibility(privateUser, "PRIVATE");
        setVisibility(withdrawnUser, "PUBLIC");

        long firstDot = insertDot(suffix + "-1", sigunguId);
        long nullCoverDot = insertDot(suffix + "-2", sigunguId);
        long tiedDot = insertDot(suffix + "-3", sigunguId);
        LocalDateTime firstLatest = LocalDateTime.parse("2026-09-03T10:00:00.123456");
        insertRecord(publicUser, insertMusic(suffix + "-public", "https://cdn.example.com/public.jpg"), firstDot,
                sigunguId, firstLatest.minusHours(1), null);
        insertRecord(privateUser, insertMusic(suffix + "-private", "https://cdn.example.com/private.jpg"), firstDot,
                sigunguId, firstLatest.plusHours(2), null);
        insertRecord(withdrawnUser, insertMusic(suffix + "-withdrawn", "https://cdn.example.com/withdrawn.jpg"),
                firstDot, sigunguId, firstLatest.plusHours(1), null);
        insertRecord(noSettingsUser, insertMusic(suffix + "-no-settings", "https://cdn.example.com/no-settings.jpg"),
                firstDot, sigunguId, firstLatest.plusHours(3), null);
        insertRecord(publicUser, insertMusic(suffix + "-deleted", "https://cdn.example.com/deleted.jpg"), firstDot,
                sigunguId, firstLatest.plusHours(4), LocalDateTime.parse("2026-09-03T11:00:00"));

        LocalDateTime nullCoverLatest = firstLatest.plusDays(1);
        insertRecord(publicUser, insertMusic(suffix + "-null-old", "https://cdn.example.com/old.jpg"), nullCoverDot,
                sigunguId, nullCoverLatest.minusHours(1), null);
        insertRecord(publicUser, insertMusic(suffix + "-null-new", null), nullCoverDot, sigunguId, nullCoverLatest,
                null);

        LocalDateTime tiedAt = firstLatest.plusDays(2);
        long lowId = insertRecord(publicUser, insertMusic(suffix + "-tie-low", "https://cdn.example.com/low.jpg"),
                tiedDot, sigunguId, tiedAt, null);
        long highId = insertRecord(publicUser,
                insertMusic(suffix + "-tie-high", "https://cdn.example.com/high.jpg"), tiedDot, sigunguId,
                tiedAt, null);

        Map<Long, LatestMapDotRecord> latestByDot = repository.findLatestPublicRecords().stream()
                .collect(Collectors.toMap(LatestMapDotRecord::mapDotId, Function.identity()));

        assertThat(latestByDot.get(firstDot).albumCoverUrl()).isEqualTo("https://cdn.example.com/public.jpg");
        assertThat(latestByDot.get(firstDot).recordedAt())
                .isEqualTo(firstLatest.minusHours(1).toInstant(java.time.ZoneOffset.UTC));
        assertThat(latestByDot.get(nullCoverDot).albumCoverUrl()).isNull();
        assertThat(latestByDot.get(nullCoverDot).recordedAt())
                .isEqualTo(nullCoverLatest.toInstant(java.time.ZoneOffset.UTC));
        assertThat(latestByDot.get(tiedDot).albumCoverUrl()).isEqualTo("https://cdn.example.com/high.jpg");
        assertThat(highId).isGreaterThan(lowId);
    }

    private long insertUser(String emailPrefix, LocalDateTime deletedAt) {
        String email = emailPrefix + "@test.local";
        jdbc.update("INSERT INTO users (email,password_hash,nickname,role,deleted_at) VALUES (?,?,?,?,?)", email,
                "test-only", "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), "USER",
                deletedAt);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insertRegion(String code, String name, String level, Long parentId) {
        jdbc.update("INSERT INTO regions (parent_id,code,name,level,is_active) VALUES (?,?,?,?,TRUE)", parentId, code,
                name, level);
        return jdbc.queryForObject("SELECT id FROM regions WHERE code=?", Long.class, code);
    }

    private void setVisibility(long userId, String visibility) {
        jdbc.update("INSERT INTO user_settings (user_id,map_visibility) VALUES (?,?)", userId, visibility);
    }

    private long insertDot(String code, long regionId) {
        jdbc.update("INSERT INTO map_dots (code,region_id,latitude,longitude,is_active) VALUES (?,?,?,?,TRUE)",
                code, regionId, 37.5, 127.0);
        Long dotId = jdbc.queryForObject("SELECT id FROM map_dots WHERE code=?", Long.class, code);
        return dotId;
    }

    private long insertMusic(String externalId, String coverUrl) {
        jdbc.update("INSERT INTO music (provider,external_music_id,title,artist_name,album_cover_url) "
                + "VALUES ('ITUNES',?,'테스트 노래','테스트 가수',?)", externalId, coverUrl);
        Long musicId = jdbc.queryForObject("SELECT id FROM music WHERE provider='ITUNES' AND external_music_id=?",
                Long.class, externalId);
        return musicId;
    }

    private long insertRecord(long userId, long musicId, long dotId, long regionId, LocalDateTime createdAt,
            LocalDateTime deletedAt) {
        jdbc.update("INSERT INTO music_records (user_id,music_id,map_dot_id,region_id,created_at,deleted_at) "
                + "VALUES (?,?,?,?,?,?)", userId, musicId, dotId, regionId, createdAt, deletedAt);
        return jdbc.queryForObject(
                "SELECT id FROM music_records WHERE user_id=? AND music_id=? AND map_dot_id=? AND created_at=?",
                Long.class, userId, musicId, dotId, createdAt);
    }
}
