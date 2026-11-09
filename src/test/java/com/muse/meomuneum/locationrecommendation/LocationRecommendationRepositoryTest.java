package com.muse.meomuneum.locationrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationRequest;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RegionResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.TrackResponse;
import com.muse.meomuneum.locationrecommendation.exception.LocationRecommendationException;
import com.muse.meomuneum.locationrecommendation.repository.LocationRecommendationRepository;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;

import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class LocationRecommendationRepositoryTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0");

    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private LocationRecommendationRepository repository;
    private long userId;
    private long sidoId;
    private RegionResponse region;

    @BeforeAll
    static void migrateSchema() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void createScope() {
        repository = new LocationRecommendationRepository(jdbc, new ObjectMapper(),
                new MusicRecordRepository(jdbc), new DataSourceTransactionManager(dataSource));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        userId = nextId();
        sidoId = nextId();
        long sigunguId = nextId();
        jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role) VALUES (?,?,?,?,?)", userId,
                suffix + "@recommendation.test", "test-only", "t" + suffix.substring(0, 8), "USER");
        jdbc.update("INSERT INTO regions (id,code,name,level,is_active) VALUES (?,?,'Seoul','SIDO',TRUE)", sidoId,
                "s" + suffix);
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level,is_active) VALUES (?,?,?,'District',"
                + "'SIGUNGU',TRUE)", sigunguId, sidoId, "g" + suffix);
        region = new RegionResponse(sigunguId, "g" + suffix, "District", "Seoul");
    }

    @Test
    void userRowLockCreatesOneCanonicalReservationAndReplaysConcurrentCancellationOnce() throws Exception {
        var context = repository.context(userId);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        RecommendationRequest firstRequest = request(context.scopeGeneration(), context.cancelEpoch());
        RecommendationRequest secondRequest = request(context.scopeGeneration(), context.cancelEpoch());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return repository.admit(userId, firstId, firstRequest, region, true);
            });
            var second = pool.submit(() -> {
                start.await();
                return repository.admit(userId, secondId, secondRequest, region, true);
            });
            start.countDown();
            var firstOutcome = first.get(5, TimeUnit.SECONDS);
            var secondOutcome = second.get(5, TimeUnit.SECONDS);

            assertThat(List.of(firstOutcome, secondOutcome).stream().filter(value -> value.invokeAi()).count())
                    .isEqualTo(1);
            assertThat(firstOutcome.canonicalRequestId()).isEqualTo(secondOutcome.canonicalRequestId());
            var joinedOutcome = firstOutcome.invokeAi() ? secondOutcome : firstOutcome;
            assertThat(joinedOutcome.response().requestId()).isEqualTo(firstOutcome.canonicalRequestId());
            assertThat(joinedOutcome.response().status()).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_states WHERE user_id=?",
                    Integer.class, userId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_requests "
                    + "WHERE user_id=? AND canonical_request_id IS NOT NULL AND status='JOINED'",
                    Integer.class, userId)).isEqualTo(1);

            UUID cancelKey = UUID.randomUUID();
            UUID aliasId = firstOutcome.canonicalRequestId().equals(firstId) ? secondId : firstId;
            var aliasRecovery = repository.get(userId, aliasId);
            assertThat(aliasRecovery.requestId()).isEqualTo(firstOutcome.canonicalRequestId());
            assertThat(aliasRecovery.status()).isEqualTo("PROCESSING");
            CountDownLatch cancelStart = new CountDownLatch(1);
            var cancel1 = pool.submit(() -> {
                cancelStart.await();
                return repository.cancel(userId, cancelKey, aliasId, context.scopeGeneration(), 0);
            });
            var cancel2 = pool.submit(() -> {
                cancelStart.await();
                return repository.cancel(userId, cancelKey, aliasId, context.scopeGeneration(), 0);
            });
            cancelStart.countDown();
            var firstCancel = cancel1.get(5, TimeUnit.SECONDS);
            var secondCancel = cancel2.get(5, TimeUnit.SECONDS);
            assertThat(firstCancel.response().cancelEpoch()).isEqualTo(1);
            assertThat(secondCancel.response().cancelEpoch()).isEqualTo(1);
            assertThat(repository.context(userId).cancelEpoch()).isEqualTo(1);
            assertThatThrownBy(() -> repository.cancel(userId, cancelKey, UUID.randomUUID(),
                    context.scopeGeneration(), 0)).isInstanceOf(LocationRecommendationException.class)
                    .satisfies(exception -> assertThat(((LocationRecommendationException) exception).reason())
                            .isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
            assertThat(repository.context(userId).cancelEpoch()).isEqualTo(1);
        }
    }

    @Test
    void cooldownStartsAtDispatchAndAllowsAnotherRegionAfterThirtyMinutes() {
        var initialContext = repository.context(userId);
        UUID cancelledId = UUID.randomUUID();
        var cancelledAdmission = repository.admit(userId, cancelledId,
                request(initialContext.scopeGeneration(), initialContext.cancelEpoch()), region, true);
        repository.cancel(userId, UUID.randomUUID(), cancelledId, initialContext.scopeGeneration(),
                initialContext.cancelEpoch());
        assertThat(repository.tryStartAiAttempt(userId, cancelledId, cancelledAdmission.scopeGeneration(),
                cancelledAdmission.admittedCancelEpoch(), deadline())).isFalse();

        var currentContext = repository.context(userId);
        UUID dispatchedId = UUID.randomUUID();
        var admission = repository.admit(userId, dispatchedId,
                request(currentContext.scopeGeneration(), currentContext.cancelEpoch()), region, true);
        assertThat(repository.tryStartAiAttempt(userId, dispatchedId, admission.scopeGeneration(),
                admission.admittedCancelEpoch(), deadline())).isTrue();
        var empty = repository.complete(userId, dispatchedId, admission.scopeGeneration(),
                admission.admittedCancelEpoch(), List.of(), null, deadline());
        assertThat(empty.status()).isEqualTo("EMPTY");

        RegionResponse anotherRegion = otherRegion();
        var coolingContext = repository.context(userId);
        var cooling = repository.admit(userId, UUID.randomUUID(),
                request(coolingContext.scopeGeneration(), coolingContext.cancelEpoch()), anotherRegion, true);
        assertThat(cooling.invokeAi()).isFalse();
        assertThat(cooling.response().status()).isEqualTo("SUPERSEDED");
        assertThat(cooling.response().reason()).isEqualTo("COOLDOWN_ACTIVE");

        jdbc.update("UPDATE location_recommendation_states SET last_ai_attempt_at="
                + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 31 MINUTE) WHERE user_id=?", userId);
        var available = repository.admit(userId, UUID.randomUUID(),
                request(coolingContext.scopeGeneration(), coolingContext.cancelEpoch()), anotherRegion, true);
        assertThat(available.invokeAi()).isTrue();
        assertThat(repository.tryStartAiAttempt(userId, available.canonicalRequestId(), available.scopeGeneration(),
                available.admittedCancelEpoch(), deadline())).isTrue();
    }

    @Test
    void failedAiAttemptAlsoStartsTheUserCooldown() {
        var context = repository.context(userId);
        UUID requestId = UUID.randomUUID();
        var admission = repository.admit(userId, requestId, request(context.scopeGeneration(), context.cancelEpoch()),
                region, true);
        assertThat(repository.tryStartAiAttempt(userId, requestId, admission.scopeGeneration(),
                admission.admittedCancelEpoch(), deadline())).isTrue();
        repository.fail(userId, requestId, admission.scopeGeneration(), admission.admittedCancelEpoch(),
                "UPSTREAM_UNAVAILABLE", deadline());

        var next = repository.admit(userId, UUID.randomUUID(),
                request(context.scopeGeneration(), context.cancelEpoch()), otherRegion(), true);

        assertThat(next.invokeAi()).isFalse();
        assertThat(next.response().reason()).isEqualTo("COOLDOWN_ACTIVE");
    }

    @Test
    void timeoutCleanupTransitionsToFailedBeforeTheLeaseExpires() {
        var context = repository.context(userId);
        UUID requestId = UUID.randomUUID();
        var admission = repository.admit(userId, requestId,
                request(context.scopeGeneration(), context.cancelEpoch()), region, true);
        assertThat(jdbc.queryForObject("SELECT lease_expires_at>UTC_TIMESTAMP(6) FROM "
                + "location_recommendation_states WHERE user_id=?", Boolean.class, userId)).isTrue();

        repository.fail(userId, requestId, admission.scopeGeneration(), admission.admittedCancelEpoch(), "TIMEOUT",
                System.nanoTime() + Duration.ofSeconds(2).toNanos());

        assertThat(repository.get(userId, requestId).status()).isEqualTo("FAILED");
        assertThat(repository.get(userId, requestId).reason()).isEqualTo("TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT lease_request_uuid FROM location_recommendation_states "
                + "WHERE user_id=?", String.class, userId)).isNull();
    }

    @Test
    void timeoutCleanupCannotOverwriteCancellation() {
        var context = repository.context(userId);
        UUID requestId = UUID.randomUUID();
        var admission = repository.admit(userId, requestId,
                request(context.scopeGeneration(), context.cancelEpoch()), region, true);
        repository.cancel(userId, UUID.randomUUID(), requestId, context.scopeGeneration(), context.cancelEpoch());

        repository.fail(userId, requestId, admission.scopeGeneration(), admission.admittedCancelEpoch(), "TIMEOUT",
                System.nanoTime() + Duration.ofSeconds(2).toNanos());

        assertThat(repository.get(userId, requestId).status()).isEqualTo("CANCELLED");
        assertThat(repository.context(userId).cancelEpoch()).isEqualTo(context.cancelEpoch() + 1);
    }

    @Test
    void timeoutCleanupCannotOverwriteAReservationFromANewerGeneration() {
        var context = repository.context(userId);
        UUID oldRequestId = UUID.randomUUID();
        var oldAdmission = repository.admit(userId, oldRequestId,
                request(context.scopeGeneration(), context.cancelEpoch()), region, true);

        UUID newGeneration = UUID.randomUUID();
        jdbc.update("UPDATE location_recommendation_states SET scope_generation=?, cancel_epoch=0, "
                + "lease_request_uuid=NULL, lease_region_id=NULL, lease_expires_at=NULL, active_request_uuid=NULL "
                + "WHERE user_id=?", newGeneration.toString(), userId);
        UUID newRequestId = UUID.randomUUID();
        var newContext = repository.context(userId);
        var newAdmission = repository.admit(userId, newRequestId,
                request(newContext.scopeGeneration(), newContext.cancelEpoch()), region, true);

        repository.fail(userId, oldRequestId, oldAdmission.scopeGeneration(), oldAdmission.admittedCancelEpoch(),
                "TIMEOUT", System.nanoTime() + Duration.ofSeconds(2).toNanos());

        assertThat(newAdmission.invokeAi()).isTrue();
        assertThat(repository.get(userId, newRequestId).status()).isEqualTo("PROCESSING");
        assertThat(repository.context(userId).activeRequestId()).isEqualTo(newRequestId);
        assertThat(repository.get(userId, oldRequestId).status()).isEqualTo("SUPERSEDED");
    }

    @Test
    void successfulIssueSurvivesCancellationAndSuppressesAnotherSameDayAttempt() {
        var context = repository.context(userId);
        UUID requestId = UUID.randomUUID();
        var admission = repository.admit(userId, requestId, request(context.scopeGeneration(), context.cancelEpoch()),
                region, true);
        assertThat(admission.invokeAi()).isTrue();
        String message = "산책에 어울리는 곡이에요.";
        TrackResponse track = new TrackResponse("42", "Song", "Artist", null, null, null);
        var ready = repository.complete(userId, admission.canonicalRequestId(), admission.scopeGeneration(),
                admission.admittedCancelEpoch(), List.of(track), message,
                System.nanoTime() + Duration.ofSeconds(20).toNanos());
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.recommendationMessage()).isEqualTo(message);
        assertThat(repository.get(userId, requestId).recommendationMessage()).isEqualTo(message);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_daily_issues "
                + "WHERE user_id=? AND region_id=? AND issued_date=DATE(UTC_TIMESTAMP(6) + INTERVAL 9 HOUR)",
                Integer.class, userId, region.regionId())).isEqualTo(1);

        repository.cancel(userId, UUID.randomUUID(), requestId, context.scopeGeneration(), 0);
        assertThat(repository.get(userId, requestId).recommendationMessage()).isNull();
        assertThat(jdbc.queryForObject("SELECT recommendation_message FROM location_recommendation_requests "
                + "WHERE user_id=? AND request_id=?", String.class, userId, requestId.toString())).isNull();
        var updatedContext = repository.context(userId);
        var next = repository.admit(userId, UUID.randomUUID(), request(updatedContext.scopeGeneration(),
                updatedContext.cancelEpoch()), region, true);

        assertThat(next.invokeAi()).isFalse();
        assertThat(next.response().status()).isEqualTo("SUPERSEDED");
        assertThat(next.response().reason()).isEqualTo("REGION_ISSUED_TODAY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_daily_issues "
                + "WHERE user_id=? AND region_id=?", Integer.class, userId, region.regionId())).isEqualTo(1);
    }

    @Test
    void localMockRepeatAllowsSameRegionOnEachMapAccessWithoutCooldownOrDailyIssue() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local-mock");
        MusicRecordRepository musicRecords = mock(MusicRecordRepository.class);
        when(musicRecords.existsActiveRecordInRegion(userId, region.regionId())).thenReturn(true);
        repository = new LocationRecommendationRepository(jdbc, new ObjectMapper(),
                musicRecords, new DataSourceTransactionManager(dataSource), environment, true);
        var context = repository.context(userId);
        UUID firstRequestId = UUID.randomUUID();
        var first = repository.admit(userId, firstRequestId,
                request(context.scopeGeneration(), context.cancelEpoch()), region, true);
        assertThat(first.invokeAi()).isTrue();
        assertThat(repository.tryStartAiAttempt(userId, first.canonicalRequestId(), first.scopeGeneration(),
                first.admittedCancelEpoch(), deadline())).isTrue();

        TrackResponse track = new TrackResponse("42", "Song", "Artist", null, null, null);
        var firstReady = repository.complete(userId, first.canonicalRequestId(), first.scopeGeneration(),
                first.admittedCancelEpoch(), List.of(track), null, deadline());
        assertThat(firstReady.status()).isEqualTo("READY");
        assertThat(repository.claim(userId, first.canonicalRequestId(), UUID.randomUUID(), first.scopeGeneration(),
                first.admittedCancelEpoch()).displayAllowed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_daily_issues "
                + "WHERE user_id=? AND region_id=?", Integer.class, userId, region.regionId())).isZero();

        var nextContext = repository.context(userId);
        var second = repository.admit(userId, UUID.randomUUID(),
                request(nextContext.scopeGeneration(), nextContext.cancelEpoch()), region, true);
        assertThat(second.invokeAi()).isTrue();
        assertThat(second.canonicalRequestId()).isNotEqualTo(first.canonicalRequestId());
        assertThat(repository.tryStartAiAttempt(userId, second.canonicalRequestId(), second.scopeGeneration(),
                second.admittedCancelEpoch(), deadline())).isTrue();
    }

    @Test
    void localRepeatFlagDoesNotBypassLimitsWhenProductionProfileIsActive() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "local-mock");
        repository = new LocationRecommendationRepository(jdbc, new ObjectMapper(),
                new MusicRecordRepository(jdbc), new DataSourceTransactionManager(dataSource),
                environment, true);
        assertReadyReplayThenDailyLimit();
    }

    @Test
    void localMockProfileKeepsLimitsWhenRepeatFlagIsDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local-mock");
        repository = new LocationRecommendationRepository(jdbc, new ObjectMapper(),
                new MusicRecordRepository(jdbc), new DataSourceTransactionManager(dataSource), environment, false);
        assertReadyReplayThenDailyLimit();
    }

    private void assertReadyReplayThenDailyLimit() {
        var context = repository.context(userId);
        var first = repository.admit(userId, UUID.randomUUID(),
                request(context.scopeGeneration(), context.cancelEpoch()), region, true);
        assertThat(repository.tryStartAiAttempt(userId, first.canonicalRequestId(), first.scopeGeneration(),
                first.admittedCancelEpoch(), deadline())).isTrue();
        var attemptAt = jdbc.queryForObject("SELECT last_ai_attempt_at FROM location_recommendation_states "
                + "WHERE user_id=?", java.sql.Timestamp.class, userId);
        TrackResponse track = new TrackResponse("42", "Song", "Artist", null, null, null);
        var ready = repository.complete(userId, first.canonicalRequestId(), first.scopeGeneration(),
                first.admittedCancelEpoch(), List.of(track), null, deadline());
        assertThat(ready.status()).isEqualTo("READY");

        // A valid READY result is reused, not a second issue or AI attempt.
        var replay = repository.admit(userId, UUID.randomUUID(),
                request(context.scopeGeneration(), context.cancelEpoch()), region, true);
        assertThat(replay.invokeAi()).isFalse();
        assertThat(replay.canonicalRequestId()).isEqualTo(first.canonicalRequestId());
        assertThat(replay.response().requestId()).isEqualTo(first.canonicalRequestId());
        assertThat(replay.response().status()).isEqualTo("READY");
        assertThat(replay.response().reason()).isNull();
        assertThat(replay.response().tracks()).containsExactly(track);
        assertThat(repository.tryStartAiAttempt(userId, replay.canonicalRequestId(), replay.scopeGeneration(),
                replay.admittedCancelEpoch(), deadline())).isFalse();

        // Once reuse expires, the same-day issue ledger still prevents a new issue.
        jdbc.update("UPDATE location_recommendation_requests SET expires_at=DATE_SUB(UTC_TIMESTAMP(6), "
                + "INTERVAL 1 SECOND) WHERE user_id=? AND request_id=?", userId,
                first.canonicalRequestId().toString());
        var next = repository.admit(userId, UUID.randomUUID(),
                request(context.scopeGeneration(), context.cancelEpoch()), region, true);
        assertThat(next.invokeAi()).isFalse();
        assertThat(next.response().status()).isEqualTo("SUPERSEDED");
        assertThat(next.response().reason()).isEqualTo("REGION_ISSUED_TODAY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_daily_issues "
                + "WHERE user_id=? AND region_id=?", Integer.class, userId, region.regionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_ai_attempt_at FROM location_recommendation_states "
                + "WHERE user_id=?", java.sql.Timestamp.class, userId)).isEqualTo(attemptAt);
    }

    @Test
    void midnightCompletionReplaysTheSameKeyAndSuppressesANewSameDayRequest() {
        var context = repository.context(userId);
        UUID requestId = UUID.randomUUID();
        var admission = repository.admit(userId, requestId, request(context.scopeGeneration(), context.cancelEpoch()),
                region, true);
        jdbc.update("UPDATE location_recommendation_requests SET scope_date=DATE_SUB("
                + "DATE(UTC_TIMESTAMP(6) + INTERVAL 9 HOUR), INTERVAL 1 DAY) "
                + "WHERE user_id=? AND request_id=?", userId, requestId.toString());
        String message = "산책에 어울리는 곡이에요.";
        TrackResponse track = new TrackResponse("42", "Song", "Artist", null, null, null);
        var ready = repository.complete(userId, admission.canonicalRequestId(), admission.scopeGeneration(),
                admission.admittedCancelEpoch(), List.of(track), message,
                System.nanoTime() + Duration.ofSeconds(20).toNanos());

        assertThat(ready.status()).isEqualTo("READY");
        var replay = repository.admit(userId, requestId, request(context.scopeGeneration(), context.cancelEpoch()),
                region, true);
        assertThat(replay.invokeAi()).isFalse();
        assertThat(replay.canonicalRequestId()).isEqualTo(requestId);
        assertThat(replay.response().status()).isEqualTo("READY");
        assertThat(replay.response().recommendationMessage()).isEqualTo(message);

        var nextAccess = repository.admit(userId, UUID.randomUUID(),
                request(context.scopeGeneration(), context.cancelEpoch()), region, true);
        assertThat(nextAccess.invokeAi()).isFalse();
        assertThat(nextAccess.response().status()).isEqualTo("SUPERSEDED");
        assertThat(nextAccess.response().reason()).isEqualTo("REGION_ISSUED_TODAY");

        jdbc.update("UPDATE location_recommendation_requests SET expires_at=DATE_SUB(UTC_TIMESTAMP(6), "
                + "INTERVAL 1 SECOND) WHERE user_id=? AND request_id=?", userId, requestId.toString());
        var expired = repository.get(userId, requestId);
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.recommendationMessage()).isNull();

        repository.cleanupExpired();
        assertThat(jdbc.queryForObject("SELECT recommendation_message FROM location_recommendation_requests "
                + "WHERE user_id=? AND request_id=?", String.class, userId, requestId.toString())).isNull();
    }

    @Test
    void sameCanonicalUuidForDifferentUsersDoesNotConflict() {
        long otherUserId = nextId();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role) VALUES (?,?,?,?,?)", otherUserId,
                suffix + "@recommendation.test", "test-only", "t" + suffix.substring(0, 8), "USER");

        var firstContext = repository.context(userId);
        var secondContext = repository.context(otherUserId);
        UUID sharedRequestId = UUID.randomUUID();
        var firstAdmission = repository.admit(userId, sharedRequestId,
                request(firstContext.scopeGeneration(), firstContext.cancelEpoch()), region, true);
        var secondAdmission = repository.admit(otherUserId, sharedRequestId,
                request(secondContext.scopeGeneration(), secondContext.cancelEpoch()), region, true);
        TrackResponse track = new TrackResponse("42", "Song", "Artist", null, null, null);

        var firstReady = repository.complete(userId, firstAdmission.canonicalRequestId(),
                firstAdmission.scopeGeneration(), firstAdmission.admittedCancelEpoch(), List.of(track), null,
                System.nanoTime() + Duration.ofSeconds(20).toNanos());
        var secondReady = repository.complete(otherUserId, secondAdmission.canonicalRequestId(),
                secondAdmission.scopeGeneration(), secondAdmission.admittedCancelEpoch(), List.of(track), null,
                System.nanoTime() + Duration.ofSeconds(20).toNanos());

        assertThat(firstReady.status()).isEqualTo("READY");
        assertThat(secondReady.status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_daily_issues "
                + "WHERE canonical_request_id=?", Integer.class, sharedRequestId.toString())).isEqualTo(2);
    }

    @Test
    void displayClaimIsIdempotentForItsKeyAndCancellationInvalidatesIt() {
        var context = repository.context(userId);
        UUID requestId = UUID.randomUUID();
        var admission = repository.admit(userId, requestId, request(context.scopeGeneration(), context.cancelEpoch()),
                region, true);
        repository.complete(userId, admission.canonicalRequestId(), admission.scopeGeneration(),
                admission.admittedCancelEpoch(), List.of(new TrackResponse("42", "Song", "Artist", null, null, null)),
                null,
                System.nanoTime() + Duration.ofSeconds(20).toNanos());
        UUID claimKey = UUID.randomUUID();

        var first = repository.claim(userId, requestId, claimKey, context.scopeGeneration(), context.cancelEpoch());
        var replay = repository.claim(userId, requestId, claimKey, context.scopeGeneration(), context.cancelEpoch());
        var competing = repository.claim(userId, requestId, UUID.randomUUID(), context.scopeGeneration(),
                context.cancelEpoch());

        assertThat(first.displayAllowed()).isTrue();
        assertThat(replay.displayAllowed()).isTrue();
        assertThat(competing.displayAllowed()).isFalse();
        assertThat(competing.reason()).isEqualTo("ALREADY_CLAIMED");

        repository.cancel(userId, UUID.randomUUID(), requestId, context.scopeGeneration(), context.cancelEpoch());
        assertThatThrownBy(() -> repository.claim(userId, requestId, claimKey, context.scopeGeneration(), 1))
                .isInstanceOf(LocationRecommendationException.class)
                .satisfies(exception -> assertThat(((LocationRecommendationException) exception).reason())
                        .isEqualTo("STALE_CONTEXT"));
        assertThat(repository.get(userId, requestId).status()).isEqualTo("CANCELLED");
    }

    @Test
    void expiredLeaseCannotFinalizeAnIssue() {
        var context = repository.context(userId);
        UUID requestId = UUID.randomUUID();
        var admission = repository.admit(userId, requestId, request(context.scopeGeneration(), context.cancelEpoch()),
                region, true);
        jdbc.update("UPDATE location_recommendation_states SET lease_expires_at=DATE_SUB(UTC_TIMESTAMP(6), "
                + "INTERVAL 1 SECOND) WHERE user_id=? AND lease_request_uuid=?", userId, requestId.toString());

        repository.complete(userId, requestId, admission.scopeGeneration(), admission.admittedCancelEpoch(),
                List.of(new TrackResponse("42", "Song", "Artist", null, null, null)), null,
                System.nanoTime() + Duration.ofSeconds(20).toNanos());

        assertThat(repository.get(userId, requestId).status()).isEqualTo("EXPIRED");
        assertThat(repository.get(userId, requestId).reason()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM location_recommendation_requests "
                + "WHERE user_id=? AND request_id=?", String.class, userId, requestId.toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_daily_issues "
                + "WHERE user_id=? AND region_id=?", Integer.class, userId, region.regionId())).isZero();
    }

    @Test
    void expiredAdmissionDeadlineDoesNotCreateRecommendationState() {
        assertThatThrownBy(() -> repository.admit(userId, UUID.randomUUID(),
                request(UUID.randomUUID(), 0), region, true, System.nanoTime() - 1))
                .isInstanceOf(LocationRecommendationException.class)
                .satisfies(exception -> assertThat(((LocationRecommendationException) exception).reason())
                        .isEqualTo("TIMEOUT"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_states WHERE user_id=?",
                Integer.class, userId)).isZero();
    }

    @Test
    void expiredDeadlinePreventsCompletionAndFailureCleanupTransactions() {
        UUID requestId = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        long expiredDeadline = System.nanoTime() - 1;

        assertThatThrownBy(() -> repository.complete(userId, requestId, generation, 0,
                List.of(new TrackResponse("42", "Song", "Artist", null, null, null)), null, expiredDeadline))
                .isInstanceOf(LocationRecommendationException.class)
                .satisfies(exception -> assertThat(((LocationRecommendationException) exception).reason())
                        .isEqualTo("TIMEOUT"));
        assertThatThrownBy(() -> repository.fail(userId, requestId, generation, 0, "UPSTREAM_UNAVAILABLE",
                expiredDeadline))
                .isInstanceOf(LocationRecommendationException.class)
                .satisfies(exception -> assertThat(((LocationRecommendationException) exception).reason())
                        .isEqualTo("TIMEOUT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_states WHERE user_id=?",
                Integer.class, userId)).isZero();
    }

    @Test
    void schemaRejectsPartialLeaseAndCleanupDeletesAliasBeforeCanonical() {
        var context = repository.context(userId);
        UUID requestId = UUID.randomUUID();
        var admission = repository.admit(userId, requestId, request(context.scopeGeneration(), context.cancelEpoch()),
                region, true);
        UUID alias = UUID.randomUUID();
        repository.admit(userId, alias, request(context.scopeGeneration(), context.cancelEpoch()), region, true);
        jdbc.update("UPDATE location_recommendation_requests SET created_at=DATE_SUB(UTC_TIMESTAMP(6), "
                + "INTERVAL 49 HOUR) WHERE user_id=?", userId);
        jdbc.update("UPDATE location_recommendation_states SET lease_request_uuid=NULL, lease_region_id=NULL, "
                + "lease_expires_at=NULL WHERE user_id=?", userId);

        long invalidUserId = nextId();
        jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role) VALUES (?,?,?,?,?)", invalidUserId,
                UUID.randomUUID().toString().substring(0, 8) + "@lease.test", "test-only",
                "t" + UUID.randomUUID().toString().substring(0, 8), "USER");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO location_recommendation_states "
                + "(user_id,scope_generation,cancel_epoch,lease_request_uuid) VALUES (?,?,0,?)", invalidUserId,
                UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("ck_location_recommendation_states_lease");
        repository.cleanupExpired();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_requests "
                + "WHERE user_id=?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM location_recommendation_states "
                + "WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
        assertThat(admission.invokeAi()).isTrue();
    }

    private RecommendationRequest request(UUID generation, long cancelEpoch) {
        return new RecommendationRequest(37.5, 127.0, 50, 0, "SESSION_START", generation, cancelEpoch);
    }

    private long nextId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private long deadline() {
        return System.nanoTime() + Duration.ofSeconds(20).toNanos();
    }

    private RegionResponse otherRegion() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long sigunguId = nextId();
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level,is_active) VALUES (?,?,?,'Other District',"
                + "'SIGUNGU',TRUE)", sigunguId, sidoId, "g" + suffix);
        return new RegionResponse(sigunguId, "g" + suffix, "Other District", "Seoul");
    }
}
