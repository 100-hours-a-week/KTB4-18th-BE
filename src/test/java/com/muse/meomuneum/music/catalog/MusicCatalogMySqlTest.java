package com.muse.meomuneum.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.location.config.LocationTokenProperties;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import com.muse.meomuneum.recommendation.service.RecommendationLocationContext;

import tools.jackson.databind.ObjectMapper;

@Testcontainers
class MusicCatalogMySqlTest {
    private static final String MIGRATION_VERSION = "20261108192420";
    private static final String VIDEO = "abcdefghijk";
    private static final String CHANNEL = "UC" + "a".repeat(22);
    private static final AtomicLong IDS = new AtomicLong(900_000_000L);
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withCommand("--log-bin-trust-function-creators=1");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private long user;
    private long other;
    private long region;
    private long parent;
    private long dot;
    private long music;
    private DataSourceTransactionManager manager;
    private RequestableMusicRepository eligibility;
    private RecommendationRepository recommendations;
    private MusicPlaybackSourceRepository sources;

    @BeforeAll
    static void upgradePreservesLegacyRowsAndDoesNotTrustExistingVideoIds() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("20261105002052")
                .locations("classpath:db/migration").load().migrate();
        jdbc.update("INSERT INTO music (provider,external_music_id,title,artist_name,youtube_video_id) "
                + "VALUES ('ITUNES','legacy','legacy','artist',?)", VIDEO);
        jdbc.update("INSERT INTO recommendation_sessions (trigger_type,input_type,conversation_key,status) "
                + "VALUES ('CHATBOT','TEXT','legacy','COMPLETED')");
        var flyway = Flyway.configure().dataSource(dataSource).target(MIGRATION_VERSION)
                .locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo(MIGRATION_VERSION);

        // 고정 버전의 업그레이드 검증 이후에는 현재 Repository와 같은 스키마를 사용한다.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(new MusicRecordRepository(jdbc).findMusic("ITUNES", "legacy").orElseThrow().is_queueable())
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT region_id FROM recommendation_sessions WHERE conversation_key='legacy'",
                Long.class)).isNull();
    }

    @BeforeEach
    void fixture() {
        manager = new DataSourceTransactionManager(dataSource);
        eligibility = new RequestableMusicRepository(jdbc);
        recommendations = new RecommendationRepository(jdbc);
        sources = new MusicPlaybackSourceRepository(jdbc, manager);
        user = IDS.incrementAndGet();
        other = IDS.incrementAndGet();
        parent = IDS.incrementAndGet();
        region = IDS.incrementAndGet();
        dot = IDS.incrementAndGet();
        for (long id : List.of(user, other)) {
            jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role) VALUES (?,?,'test',?,'USER')",
                    id, id + "@catalog.test", "t" + id);
        }
        jdbc.update("INSERT INTO regions (id,code,name,level) VALUES (?,?,'parent','SIDO')", parent, "p" + parent);
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level) VALUES (?,?,?,'region','SIGUNGU')",
                region, parent, "r" + region);
        jdbc.update("INSERT INTO map_dots (id,code,region_id,latitude,longitude) VALUES (?,?,?,37,127)",
                dot, "d" + dot, region);
        music = recommendations.saveMusic(track("" + user));
    }

    @Test
    void eligibilityRequiresVerifiedSourceAndProtectsPrivateDeletedAndOtherUserProvenance() {
        long record = IDS.incrementAndGet();
        jdbc.update("INSERT INTO music_records (id,user_id,music_id,map_dot_id,region_id) VALUES (?,?,?,?,?)",
                record, other, music, dot, region);
        jdbc.update("UPDATE music SET youtube_video_id=? WHERE id=?", VIDEO, music);
        assertThat(eligibility.findSources(user, region, music)).isEmpty();
        sources.save(review(0), video(), Instant.now());
        assertThat(eligibility.findSources(user, region, music)).isEmpty();
        assertThat(eligibility.findSources(other, region, music).orElseThrow().recorded()).isTrue();
        jdbc.update("INSERT INTO user_settings (user_id,map_visibility) VALUES (?,'PUBLIC')", other);
        assertThat(eligibility.findSources(user, region, music).orElseThrow().recorded()).isTrue();
        jdbc.update("UPDATE music_records SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?", record);
        assertThat(eligibility.findSources(user, region, music)).isEmpty();
        long session = session(user, region);
        assertThat(eligibility.findSources(user, region, music)).isEmpty();
        new TransactionTemplate(manager)
                .execute(tx -> recommendations.completeSession(session, request(), List.of(track("" + user))));
        assertThat(eligibility.findSources(user, region, music).orElseThrow().recommended()).isTrue();
        assertThat(eligibility.findSources(other, region, music)).isEmpty();
        jdbc.update("UPDATE music_records SET deleted_at=NULL WHERE id=?", record);
        var both = eligibility.findSources(user, region, music).orElseThrow();
        assertThat(both.recorded()).isTrue();
        assertThat(both.recommended()).isTrue();
        assertThat(eligibility.findSources(user, parent, music)).isEmpty();
        jdbc.update("UPDATE users SET deleted_at=UTC_TIMESTAMP() WHERE id=?", user);
        assertThat(eligibility.findSources(user, region, music)).isEmpty();
    }

    @Test
    void sourceRefreshUsesVersionAndExpiresOrDisablesWithoutChangingOtherMetadata() {
        sources.save(review(0), video(), Instant.now());
        var records = new MusicRecordRepository(jdbc);
        assertThat(records.findMusic("ITUNES", "" + user).orElseThrow().is_queueable()).isTrue();
        assertThatThrownBy(() -> sources.save(review(0), video(), Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        sources.disable(music, 1);
        assertThat(records.findMusic("ITUNES", "" + user).orElseThrow().is_queueable()).isFalse();
        sources.save(review(2), video(), Instant.now().minusSeconds(3 * 86400));
        assertThat(records.findMusic("ITUNES", "" + user).orElseThrow().is_queueable()).isFalse();
        assertThat(records.findMusic("ITUNES", "" + user).orElseThrow().title()).isEqualTo("Song");
        assertThatThrownBy(() -> sources.save(review(3), new YouTubeVideoVerifier.Video(VIDEO, "other", 10),
                Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validSignedLocationIsBoundToUserSessionExpiryAndActiveRegion() {
        var sido = mock(Region.class);
        when(sido.getId()).thenReturn(parent);
        when(sido.getCode()).thenReturn("p" + parent);
        var sigungu = mock(Region.class);
        when(sigungu.getId()).thenReturn(region);
        when(sigungu.getCode()).thenReturn("r" + region);
        var tokens = new LocationResolutionTokenProvider(new LocationTokenProperties("test-secret-".repeat(4)),
                Clock.systemUTC());
        String sid = UUID.randomUUID().toString();
        String token = tokens.issueForSession(user, sid, sido, sigungu, dot).value();
        var resolver = new RecommendationLocationContext(tokens, jdbc);
        var claims = resolver.resolve(token, user, sid);
        long session = recommendations.createSession(request(), null, user, Instant.now(),
                claims.sigunguRegionId(), claims.mapDotId());
        assertThat(jdbc.queryForObject("SELECT region_id FROM recommendation_sessions WHERE id=?", Long.class,
                session)).isEqualTo(region);
        assertThatThrownBy(() -> resolver.resolve(token, other, sid)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> resolver.resolve(token, user, UUID.randomUUID().toString()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> resolver.resolve(token + "tampered", user, sid)).isInstanceOf(RuntimeException.class);
        var later = new LocationResolutionTokenProvider(new LocationTokenProperties("test-secret-".repeat(4)),
                Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(6)));
        assertThatThrownBy(() -> new RecommendationLocationContext(later, jdbc).resolve(token, user, sid))
                .isInstanceOf(RuntimeException.class);
        jdbc.update("UPDATE regions SET is_active=FALSE WHERE id=?", region);
        assertThatThrownBy(() -> resolver.resolve(token, user, sid)).isInstanceOf(RuntimeException.class);
        long legacy = recommendations.createSession(request(), "guest", null, Instant.now());
        assertThat(jdbc.queryForObject("SELECT region_id FROM recommendation_sessions WHERE id=?", Long.class,
                legacy)).isNull();
    }

    @Test
    void popupExpiryAndCancellationDoNotDeleteCompletedProvenance() {
        var popup = popup();
        UUID id = UUID.randomUUID();
        var context = popup.context(user);
        var admission = popup.admit(user, id, popupRequest(context), popupRegion(), true);
        assertThat(popup.complete(user, id, admission.scopeGeneration(), admission.admittedCancelEpoch(),
                List.of(popupTrack("" + user)), null, deadline()).status()).isEqualTo("READY");
        sources.save(review(0), video(), Instant.now());
        assertThat(eligibility.findSources(user, region, music).orElseThrow().recommended()).isTrue();
        popup.cancel(user, UUID.randomUUID(), id, context.scopeGeneration(), context.cancelEpoch());
        jdbc.update("UPDATE location_recommendation_requests SET expires_at=DATE_SUB(UTC_TIMESTAMP(6), "
                + "INTERVAL 1 DAY),tracks=JSON_ARRAY() WHERE request_id=?", id.toString());
        assertThat(eligibility.findSources(user, region, music).orElseThrow().recommended()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_sessions WHERE location_request_id=?",
                Integer.class, id.toString())).isEqualTo(1);
    }

    @Test
    void archiveFailureRollsBackReadyDailyIssueSessionAndNewMusicTogether() {
        var popup = popup();
        UUID id = UUID.randomUUID();
        var context = popup.context(user);
        var admission = popup.admit(user, id, popupRequest(context), popupRegion(), true);
        jdbc.execute("CREATE TRIGGER reject_catalog_item BEFORE INSERT ON recommendation_items FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only archive failure'");
        try {
            assertThatThrownBy(() -> popup.complete(user, id, admission.scopeGeneration(),
                    admission.admittedCancelEpoch(), List.of(popupTrack("new-rollback")), null, deadline()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_catalog_item");
        }
        assertThat(popup.get(user, id).status()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_sessions WHERE location_request_id=?",
                Integer.class, id.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music WHERE external_music_id='new-rollback'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_daily_issues WHERE user_id=?",
                Integer.class, user)).isZero();
    }

    private long session(long owner, long area) {
        return recommendations.createSession(request(), null, owner, Instant.now(), area, null);
    }

    private RecommendationRequest request() {
        return new RecommendationRequest("TEXT", "CHATBOT", UUID.randomUUID().toString(), "test");
    }

    private TrackData track(String id) {
        return new TrackData("ITUNES", id, "Song", "Artist", null, null);
    }

    private MusicPlaybackSourceRepository.Review review(long version) {
        return new MusicPlaybackSourceRepository.Review(music, VIDEO, CHANNEL, "TOPIC",
                "test-only human review", "test-reviewer", 1, version);
    }

    private YouTubeVideoVerifier.Video video() {
        return new YouTubeVideoVerifier.Video(VIDEO, CHANNEL, 180000);
    }

    private LocationRecommendationRepository popup() {
        return new LocationRecommendationRepository(jdbc, new ObjectMapper(), new MusicRecordRepository(jdbc), manager);
    }

    private LocationRecommendationDtos.RegionResponse popupRegion() {
        return new LocationRecommendationDtos.RegionResponse(region, "r" + region, "region", "parent");
    }

    private LocationRecommendationDtos.RecommendationRequest popupRequest(
            LocationRecommendationDtos.ContextResponse context) {
        return new LocationRecommendationDtos.RecommendationRequest(37, 127, 1, 1, "CHAT_ENTER",
                context.scopeGeneration(), context.cancelEpoch());
    }

    private LocationRecommendationDtos.TrackResponse popupTrack(String id) {
        return new LocationRecommendationDtos.TrackResponse(id, "Song", "Artist", null, null, null);
    }

    private long deadline() {
        return System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
    }
}
