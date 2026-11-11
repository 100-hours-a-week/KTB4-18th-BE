package com.muse.meomuneum.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.chat.region.domain.Region;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.location.config.LocationTokenProperties;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.recommendation.controller.RecommendationController;
import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.ImageRecommendationRequest;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.exception.RecommendationExceptionHandler;
import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.recommendation.service.RecommendationEventStream;
import com.muse.meomuneum.recommendation.service.RecommendationLocationContext;
import com.muse.meomuneum.recommendation.service.RecommendationService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        assertThat(eligibility.findSources(user, region, music)).isEmpty();
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
        assertThat(both.recorded()).isFalse();
        assertThat(both.recommended()).isTrue();
        assertThat(eligibility.findSources(user, parent, music)).isEmpty();
        jdbc.update("UPDATE users SET deleted_at=UTC_TIMESTAMP() WHERE id=?", user);
        assertThat(eligibility.findSources(user, region, music)).isEmpty();
    }

    @Test
    void ownUnregisteredSongsAreListedWithLocalizedSearchAndNoLegacyAdmission() {
        jdbc.update("INSERT INTO music_records(user_id,music_id,map_dot_id,region_id) VALUES (?,?,?,?)",
                user, music, dot, region);
        jdbc.update("UPDATE music SET title_ko='네온',artist_name_ko='밤 가수',title_us='Neon Light',"
                + "artist_name_us='Night Singer' WHERE id=?", music);
        assertThat(eligibility.findProvenance(user, region, music).orElseThrow().recorded()).isTrue();
        assertThat(eligibility.findSources(user, region, music)).isEmpty();
        for (String query : List.of("", "NEON LIGHT", "neonlight", "밤　가수", "네온", "NIGHT SINGER")) {
            var rows = eligibility.search(user, region, query, 0, 21);
            assertThat(rows).as(query).hasSize(1);
            assertThat(rows.getFirst().get("title")).isEqualTo("네온");
            assertThat(rows.getFirst().get("youtube_video_id")).isNull();
            assertThat(rows.getFirst().get("sources")).isEqualTo(List.of("RECORDED"));
        }
        assertThat(eligibility.search(user, region, "%", 0, 21)).isEmpty();
        assertThat(eligibility.search(user, region, "", music, 21)).isEmpty();
        assertThat(eligibility.search(other, region, "", 0, 21)).isEmpty();
        assertThat(eligibility.search(user, parent, "", 0, 21)).isEmpty();
        jdbc.update("UPDATE music_records SET deleted_at=UTC_TIMESTAMP(6) WHERE user_id=?", user);
        assertThat(eligibility.search(user, region, "", 0, 21)).isEmpty();
    }

    @Test
    void allCompletedRecommendationInputsKeepOwnRegionAndDeduplicateProvenance() {
        for (String input : List.of("TEXT", "VOICE")) {
            var request = new RecommendationRequest(input, "CHATBOT", UUID.randomUUID().toString(), "test");
            long id = recommendations.createSession(request, null, user, Instant.now(), region, dot);
            assertThat(eligibility.search(user, region, "", 0, 21)).hasSize(input.equals("TEXT") ? 0 : 1);
            recommendations.completeSession(id, request, List.of(track("" + user)));
        }
        var image = new ImageRecommendationRequest(UUID.randomUUID().toString(), "IMAGE", "CHATBOT");
        long id = recommendations.createImageSession(image, user, Instant.now(), region, dot);
        recommendations.completeImageSession(id, image.conversation_key(), List.of(track("" + user)));
        assertThat(jdbc.queryForMap("SELECT region_id,map_dot_id FROM recommendation_sessions WHERE id=?", id))
                .containsEntry("region_id", region).containsEntry("map_dot_id", dot);
        assertThat(eligibility.search(user, region, "", 0, 21)).hasSize(1);
        assertThat(eligibility.search(other, region, "", 0, 21)).isEmpty();
        long legacy = recommendations.createImageSession(image, other, Instant.now());
        recommendations.completeImageSession(legacy, image.conversation_key(), List.of(track("" + user)));
        assertThat(eligibility.search(other, region, "", 0, 21)).isEmpty();
        jdbc.update("INSERT INTO music_records(user_id,music_id,map_dot_id,region_id) VALUES (?,?,?,?)",
                user, music, dot, region);
        assertThat(eligibility.search(user, region, "", 0, 21).getFirst().get("sources"))
                .isEqualTo(List.of("RECORDED", "RECOMMENDED"));
        jdbc.update("UPDATE recommendation_sessions SET status='FAILED' WHERE user_id=?", user);
        jdbc.update("DELETE FROM music_records WHERE user_id=?", user);
        assertThat(eligibility.search(user, region, "", 0, 21)).isEmpty();
    }

    @Test
    void keysetPagesReachEveryEligibleSongAndIgnoreUnknownRecommendationTriggers() {
        for (int n = 0; n < 24; n++) {
            long song = recommendations.saveMusic(track(user + "-" + n));
            jdbc.update("INSERT INTO music_records(user_id,music_id,map_dot_id,region_id) VALUES (?,?,?,?)",
                    user, song, dot, region);
        }
        var first = eligibility.search(user, region, "", 0, 20);
        long after = ((Number) first.getLast().get("music_id")).longValue();
        var last = eligibility.search(user, region, "", after, 20);
        assertThat(first).hasSize(20);
        assertThat(last).hasSize(4);
        assertThat(last).allSatisfy(row -> assertThat(((Number) row.get("music_id")).longValue()).isGreaterThan(after));
        long id = session(other, region);
        recommendations.completeSession(id, request(), List.of(track("" + user)));
        jdbc.update("UPDATE recommendation_sessions SET trigger_type='UNKNOWN' WHERE id=?", id);
        assertThat(eligibility.search(other, region, "", 0, 21)).isEmpty();
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
        assertInvalidLocation(() -> resolver.resolve(token, other, sid));
        assertInvalidLocation(() -> resolver.resolve(token, null, sid));
        assertInvalidLocation(() -> resolver.resolve(token, user, null));
        assertInvalidLocation(() -> resolver.resolve(token, user, UUID.randomUUID().toString()));
        assertInvalidLocation(() -> resolver.resolve(token + "tampered", user, sid));
        var later = new LocationResolutionTokenProvider(new LocationTokenProperties("test-secret-".repeat(4)),
                Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(6)));
        assertInvalidLocation(() -> new RecommendationLocationContext(later, jdbc).resolve(token, user, sid));
        jdbc.update("UPDATE regions SET is_active=FALSE WHERE id=?", region);
        assertInvalidLocation(() -> resolver.resolve(token, user, sid));
        long legacy = recommendations.createSession(request(), "guest", null, Instant.now());
        assertThat(jdbc.queryForObject("SELECT region_id FROM recommendation_sessions WHERE id=?", Long.class,
                legacy)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEXT", "VOICE"})
    void sameMapDotKeepsDifferentUsersRecommendationsInTheirOwnRegions(String inputType) throws Exception {
        long secondRegion = IDS.incrementAndGet();
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level) VALUES (?,?,?,'second','SIGUNGU')",
                secondRegion, parent, "r" + secondRegion);

        long firstSession = admitSignedRecommendation(inputType, user, region);
        long secondSession = admitSignedRecommendation(inputType, other, secondRegion);
        assertThat(jdbc.queryForMap("SELECT region_id,map_dot_id FROM recommendation_sessions WHERE id=?",
                firstSession)).containsEntry("region_id", region).containsEntry("map_dot_id", dot);
        assertThat(jdbc.queryForMap("SELECT region_id,map_dot_id FROM recommendation_sessions WHERE id=?",
                secondSession)).containsEntry("region_id", secondRegion).containsEntry("map_dot_id", dot);
        assertThat(jdbc.queryForObject("SELECT region_id FROM map_dots WHERE id=?", Long.class, dot))
                .isEqualTo(region);

        recommendations.completeSession(firstSession, request(), List.of(track("" + user)));
        recommendations.completeSession(secondSession, request(), List.of(track("" + user)));
        assertThat(eligibility.findProvenance(user, region, music).orElseThrow().recommended()).isTrue();
        assertThat(eligibility.findProvenance(other, secondRegion, music).orElseThrow().recommended()).isTrue();
        assertThat(eligibility.findProvenance(user, secondRegion, music)).isEmpty();
        assertThat(eligibility.findProvenance(other, region, music)).isEmpty();
    }

    @Test
    void signedLocationStillRequiresActiveRegionHierarchyAndExistingActiveDot() {
        var tokens = new LocationResolutionTokenProvider(new LocationTokenProperties("test-secret-".repeat(4)),
                Clock.systemUTC());
        String sid = UUID.randomUUID().toString();
        String token = signedLocation(tokens, user, sid, region, dot);
        var resolver = new RecommendationLocationContext(tokens, jdbc);

        for (long id : List.of(parent, region)) {
            jdbc.update("UPDATE regions SET is_active=FALSE WHERE id=?", id);
            assertInvalidLocation(() -> resolver.resolve(token, user, sid));
            jdbc.update("UPDATE regions SET is_active=TRUE WHERE id=?", id);
        }
        jdbc.update("UPDATE map_dots SET is_active=FALSE WHERE id=?", dot);
        assertInvalidLocation(() -> resolver.resolve(token, user, sid));
        jdbc.update("UPDATE map_dots SET is_active=TRUE WHERE id=?", dot);
        String missingDot = signedLocation(tokens, user, sid, region, IDS.incrementAndGet());
        assertInvalidLocation(() -> resolver.resolve(missingDot, user, sid));
        String missingRegion = signedLocation(tokens, user, sid, IDS.incrementAndGet(), dot);
        assertInvalidLocation(() -> resolver.resolve(missingRegion, user, sid));
        jdbc.update("UPDATE regions SET parent_id=? WHERE id=?", region, region);
        assertInvalidLocation(() -> resolver.resolve(token, user, sid));
        jdbc.update("UPDATE regions SET parent_id=? WHERE id=?", parent, region);
        jdbc.update("UPDATE regions SET code=CONCAT(code,'-changed') WHERE id=?", region);
        assertInvalidLocation(() -> resolver.resolve(token, user, sid));
    }

    private long admitSignedRecommendation(String inputType, long owner, long area) throws Exception {
        var tokens = new LocationResolutionTokenProvider(new LocationTokenProperties("test-secret-".repeat(4)),
                Clock.systemUTC());
        String sid = UUID.randomUUID().toString();
        String token = signedLocation(tokens, owner, sid, area, dot);
        var auth = UsernamePasswordAuthenticationToken.authenticated(owner, null, List.of());
        auth.setDetails(new TokenClaims(owner, List.of("USER"), Instant.now().plusSeconds(300), sid, "jti"));
        // Admission is synchronous; provider execution is outside this location regression.
        var service = new RecommendationService(mock(RecommendationProvider.class), recommendations,
                new SimpleMeterRegistry(), mock(RecommendationEventStream.class), task -> {
                }, new RecommendationLocationContext(tokens, jdbc));
        var mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(service, new CurrentUserResolver()))
                .setControllerAdvice(new RecommendationExceptionHandler()).build();
        var request = new RecommendationRequest(inputType, "CHATBOT", UUID.randomUUID().toString(), "test", token);
        var mapper = new ObjectMapper();
        var result = mvc.perform(post("/api/v1/recommendations").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isAccepted()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("recommendation_id")
                .asLong();
    }

    private String signedLocation(LocationResolutionTokenProvider tokens, long owner, String sid, long area,
            long mapDot) {
        var sido = mock(Region.class);
        when(sido.getId()).thenReturn(parent);
        when(sido.getCode()).thenReturn("p" + parent);
        var sigungu = mock(Region.class);
        when(sigungu.getId()).thenReturn(area);
        when(sigungu.getCode()).thenReturn("r" + area);
        return tokens.issueForSession(owner, sid, sido, sigungu, mapDot).value();
    }

    private void assertInvalidLocation(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(RecommendationException.class)
                .hasMessage("invalid location resolution token")
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(400);
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
