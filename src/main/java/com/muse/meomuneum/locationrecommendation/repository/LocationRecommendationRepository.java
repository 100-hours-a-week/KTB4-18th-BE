package com.muse.meomuneum.locationrecommendation.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.location.provider.ResolvedRegionCode;
import com.muse.meomuneum.locationrecommendation.domain.LocationRecommendationStatus;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.CancellationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.ContextResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.DisplayClaimResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RegionResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.TrackResponse;
import com.muse.meomuneum.locationrecommendation.exception.LocationRecommendationException;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class LocationRecommendationRepository {
    private static final long RESULT_TTL_MILLIS = 600_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MusicRecordRepository musicRecords;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transaction;
    private final boolean testRepeatEnabled;

    public LocationRecommendationRepository(JdbcTemplate jdbc, ObjectMapper mapper,
            MusicRecordRepository musicRecords, PlatformTransactionManager transactionManager) {
        this(jdbc, mapper, musicRecords, transactionManager, null, false);
    }

    @Autowired
    public LocationRecommendationRepository(JdbcTemplate jdbc, ObjectMapper mapper,
            MusicRecordRepository musicRecords, PlatformTransactionManager transactionManager,
            Environment environment,
            @Value("${location-recommendation.test-repeat-enabled:false}") boolean configuredTestRepeatEnabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.musicRecords = musicRecords;
        this.transactionManager = transactionManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.testRepeatEnabled = configuredTestRepeatEnabled && environment != null
                && environment.acceptsProfiles(Profiles.of("local-mock & !prod"));
    }

    public Optional<RegionResponse> findActiveRegion(ResolvedRegionCode codes) {
        return jdbc.query("SELECT g.id region_id, g.code, g.name, p.name sido_name FROM regions g "
                + "JOIN regions p ON p.id=g.parent_id WHERE g.code=? AND p.code=? AND g.level='SIGUNGU' "
                + "AND p.level='SIDO' AND g.is_active=TRUE AND p.is_active=TRUE LIMIT 1",
                (row, index) -> new RegionResponse(row.getLong("region_id"), row.getString("code"),
                        row.getString("name"), row.getString("sido_name")),
                codes.sigunguCode(), codes.sidoCode())
                .stream().findFirst();
    }

    public ContextResponse context(long userId) {
        return inTransaction(() -> {
            State state = lockState(userId);
            DbDate dbDate = dbDate();
            UUID activeRequest = state.activeRequestId();
            if (activeRequest == null || state.leaseExpiresAt() == null || state.leaseRequestId() == null) {
                activeRequest = null;
            } else if (!leaseIsActive(userId, state.leaseRequestId(), state.generation(), state.cancelEpoch())) {
                activeRequest = null;
            }
            return new ContextResponse(state.generation(), state.cancelEpoch(), dbDate.kstDate().toString(),
                    activeRequest);
        });
    }

    public AdmissionOutcome admit(long userId, UUID requestId,
            com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationRequest request,
            RegionResponse region, boolean enabled) {
        return admit(userId, requestId, request, region, enabled, (Long) null);
    }

    public AdmissionOutcome admit(long userId, UUID requestId,
            com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationRequest request,
            RegionResponse region, boolean enabled, long deadlineNanos) {
        return admit(userId, requestId, request, region, enabled, Long.valueOf(deadlineNanos));
    }

    private AdmissionOutcome admit(long userId, UUID requestId,
            com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationRequest request,
            RegionResponse region, boolean enabled, Long deadlineNanos) {
        Supplier<AdmissionOutcome> action = () -> {
            State state = lockState(userId);
            DbDate dbDate = dbDate();
            requireCurrentContext(state, request.scopeGeneration(), request.cancelEpoch());

            Request existing = findByIdempotencyKey(userId, requestId).orElse(null);
            if (existing != null) {
                if (existing.regionId() != region.regionId()) {
                    throw conflict("KEY_SCOPE_MISMATCH", "request key does not match the current location scope");
                }
                Request root = resolveRoot(existing);
                return new AdmissionOutcome(false, root.requestId(), root.admittedCancelEpoch(), root.generation(),
                        response(root, state, userId));
            }

            if (!testRepeatEnabled && musicRecords.existsActiveRecordInRegion(userId, region.regionId())) {
                Request skipped = insertTerminalRequest(userId, requestId, requestId, state, region.regionId(),
                        dbDate.kstDate(), LocationRecommendationStatus.SUPERSEDED, "RECORDED_REGION");
                return outcome(skipped, state, userId);
            }

            Request candidate = findReusable(userId, region.regionId(), dbDate.kstDate(), state).orElse(null);
            if (candidate != null) {
                Request root = resolveRoot(candidate);
                if (root.status() == LocationRecommendationStatus.PROCESSING
                        && leaseIsActive(userId, root.requestId(), state.generation(), state.cancelEpoch())) {
                    Request alias = insertAlias(userId, requestId, requestId, root, state, region.regionId(),
                            dbDate.kstDate());
                    return new AdmissionOutcome(false, root.requestId(), root.admittedCancelEpoch(), root.generation(),
                            response(root, state, userId));
                }
                if (!testRepeatEnabled && root.status() == LocationRecommendationStatus.READY && !isExpired(root)) {
                    Request alias = insertAlias(userId, requestId, requestId, root, state, region.regionId(),
                            dbDate.kstDate());
                    return new AdmissionOutcome(false, root.requestId(), root.admittedCancelEpoch(), root.generation(),
                            response(root, state, userId));
                }
            }

            if (state.leaseRequestId() != null
                    && leaseIsActive(userId, state.leaseRequestId(), state.generation(), state.cancelEpoch())) {
                Request busy = insertTerminalRequest(userId, requestId, requestId, state, region.regionId(),
                        dbDate.kstDate(), LocationRecommendationStatus.SUPERSEDED, "BUSY_OTHER_REGION");
                return outcome(busy, state, userId);
            }

            if (!enabled) {
                Request disabled = insertTerminalRequest(userId, requestId, requestId, state, region.regionId(),
                        dbDate.kstDate(), LocationRecommendationStatus.SUPERSEDED, "FEATURE_DISABLED");
                return outcome(disabled, state, userId);
            }

            Request issued = testRepeatEnabled
                    ? null
                    : findIssuedRequest(userId, region.regionId(), dbDate.kstDate()).orElse(null);
            if (issued != null) {
                Request root = resolveRoot(issued);
                if (root.status() == LocationRecommendationStatus.READY && !isExpired(root)
                        && root.generation().equals(state.generation())
                        && root.admittedCancelEpoch() == state.cancelEpoch()
                        && root.scopeDate().equals(dbDate.kstDate())) {
                    Request alias = insertAlias(userId, requestId, requestId, root, state, region.regionId(),
                            dbDate.kstDate());
                    return new AdmissionOutcome(false, root.requestId(), root.admittedCancelEpoch(), root.generation(),
                            response(root, state, userId));
                }
                Request skipped = insertTerminalRequest(userId, requestId, requestId, state, region.regionId(),
                        dbDate.kstDate(), LocationRecommendationStatus.SUPERSEDED, "REGION_ISSUED_TODAY");
                return outcome(skipped, state, userId);
            }

            if (!testRepeatEnabled && state.cooldownActive()) {
                Request cooldown = insertTerminalRequest(userId, requestId, requestId, state, region.regionId(),
                        dbDate.kstDate(), LocationRecommendationStatus.SUPERSEDED, "COOLDOWN_ACTIVE");
                return outcome(cooldown, state, userId);
            }

            if (state.leaseRequestId() != null) {
                expireReservation(userId, state.leaseRequestId());
                jdbc.update("UPDATE location_recommendation_states SET lease_request_uuid=NULL, lease_region_id=NULL, "
                        + "lease_expires_at=NULL, active_request_uuid=NULL, updated_at=UTC_TIMESTAMP(6) "
                        + "WHERE user_id=? AND lease_request_uuid=? AND lease_expires_at<=UTC_TIMESTAMP(6)",
                        userId, state.leaseRequestId().toString());
            }

            Request created = insertProcessingRequest(userId, requestId, state, region.regionId(), dbDate.kstDate());
            int reserved = jdbc.update("UPDATE location_recommendation_states SET lease_request_uuid=?, "
                    + "lease_region_id=?, lease_expires_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 150 SECOND), "
                    + "active_request_uuid=?, updated_at=UTC_TIMESTAMP(6) WHERE user_id=? "
                    + "AND cancel_epoch=? AND scope_generation=? AND (lease_request_uuid IS NULL "
                    + "OR lease_expires_at<=UTC_TIMESTAMP(6))", requestId.toString(), region.regionId(),
                    requestId.toString(), userId, state.cancelEpoch(), state.generation().toString());
            if (reserved != 1) {
                throw conflict("BUSY_OTHER_REGION", "a recommendation request is already in progress");
            }
            return new AdmissionOutcome(true, created.requestId(), created.admittedCancelEpoch(), created.generation(),
                    null);
        };
        return deadlineNanos == null ? inTransaction(action) : inTransaction(deadlineNanos, action);
    }

    public RecommendationResponse complete(long userId, UUID requestId, UUID generation, long cancelEpoch,
            List<TrackResponse> tracks, String recommendationMessage, long deadlineNanos) {
        try {
            return inTransaction(deadlineNanos, () -> {
                State state = lockState(userId);
                Request root = findByRequestId(userId, requestId)
                        .map(this::resolveRoot).orElseThrow(() -> notFound());
                if (System.nanoTime() - deadlineNanos >= 0) {
                    failRootIfOwned(userId, root, state, "TIMEOUT");
                    return response(resolveRoot(findByRequestId(userId, requestId).orElse(root)), state, userId);
                }
                if (!ownsLiveLease(userId, root, state, generation, cancelEpoch)) {
                    return response(root, state, userId);
                }
                if (!testRepeatEnabled && musicRecords.existsActiveRecordInRegion(userId, root.regionId())) {
                    updateTerminalOwned(root, LocationRecommendationStatus.SUPERSEDED, "RECORDED_REGION", true);
                    releaseLease(userId, requestId);
                } else if (tracks.isEmpty()) {
                    updateTerminalOwned(root, LocationRecommendationStatus.EMPTY, null, true);
                    releaseLease(userId, requestId);
                } else {
                    insertDailyIssue(userId, root, tracks, recommendationMessage, generation, cancelEpoch,
                            deadlineNanos);
                    releaseLease(userId, requestId);
                }
                State latest = readState(userId);
                Request completed = resolveRoot(findByRequestId(userId, requestId).orElse(root));
                return response(completed, latest, userId);
            });
        } catch (ReservationLostException exception) {
            try {
                fail(userId, requestId, generation, cancelEpoch, "TIMEOUT", deadlineNanos);
            } catch (RuntimeException ignored) {
            }
            throw timeoutException();
        }
    }

    public void fail(long userId, UUID requestId, UUID generation, long cancelEpoch, String reason,
            long deadlineNanos) {
        inTransaction(deadlineNanos, () -> {
            State state = lockState(userId);
            Request root = findByRequestId(userId, requestId).map(this::resolveRoot).orElse(null);
            if (root == null || root.status() != LocationRecommendationStatus.PROCESSING
                    || !root.generation().equals(generation) || root.admittedCancelEpoch() != cancelEpoch
                    || !state.generation().equals(generation) || state.cancelEpoch() != cancelEpoch
                    || !requestId.equals(state.leaseRequestId())) {
                return null;
            }
            updateTerminalOwned(root, LocationRecommendationStatus.FAILED,
                    System.nanoTime() - deadlineNanos >= 0 ? "TIMEOUT" : reason, false);
            releaseLease(userId, requestId);
            return null;
        });
    }

    public RecommendationResponse get(long userId, UUID requestedId) {
        return inTransaction(() -> {
            State state = lockState(userId);
            Request requested = findByRequestId(userId, requestedId).orElseThrow(() -> notFound());
            Request root = resolveRoot(requested);
            normalizeExpiredProcessing(userId, root, state);
            root = resolveRoot(findByRequestId(userId, requestedId).orElse(root));
            return response(root, state, userId);
        });
    }

    public boolean stillOwnsLease(long userId, UUID requestId, UUID generation, long cancelEpoch) {
        return inTransaction(() -> {
            State state = lockState(userId);
            Request request = findByRequestId(userId, requestId).map(this::resolveRoot).orElse(null);
            return request != null && ownsLiveLease(userId, request, state, generation, cancelEpoch);
        });
    }

    public boolean tryStartAiAttempt(long userId, UUID requestId, UUID generation, long cancelEpoch,
            long deadlineNanos) {
        return inTransaction(deadlineNanos, () -> {
            State state = lockState(userId);
            Request request = findByRequestId(userId, requestId).map(this::resolveRoot).orElse(null);
            if (request == null || !ownsLiveLease(userId, request, state, generation, cancelEpoch)) {
                return false;
            }
            if (!testRepeatEnabled && state.cooldownActive()) {
                updateTerminalOwned(request, LocationRecommendationStatus.SUPERSEDED, "COOLDOWN_ACTIVE", true);
                releaseLease(userId, requestId);
                return false;
            }
            String cooldownPredicate = testRepeatEnabled
                    ? ""
                    : " AND (last_ai_attempt_at IS NULL OR "
                            + "last_ai_attempt_at<=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 MINUTE))";
            int updated = jdbc.update("UPDATE location_recommendation_states SET last_ai_attempt_at=UTC_TIMESTAMP(6), "
                    + "updated_at=UTC_TIMESTAMP(6) WHERE user_id=? AND lease_request_uuid=? "
                    + "AND scope_generation=? AND cancel_epoch=? AND lease_expires_at>UTC_TIMESTAMP(6)"
                    + cooldownPredicate, userId, requestId.toString(), generation.toString(), cancelEpoch);
            if (updated != 1) {
                throw integrityError();
            }
            return true;
        });
    }

    public DisplayClaimResponse claim(long userId, UUID requestedId, UUID claimKey, UUID generation,
            long cancelEpoch) {
        return inTransaction(() -> {
            State state = lockState(userId);
            Request requested = findByRequestId(userId, requestedId).orElseThrow(() -> notFound());
            Request root = resolveRoot(requested);
            if (!state.generation().equals(generation) || state.cancelEpoch() != cancelEpoch) {
                throw conflict("STALE_CONTEXT", "recommendation context is stale");
            }
            UUID priorClaimRequestId = findDisplayClaimRequest(userId, claimKey).orElse(null);
            if (priorClaimRequestId != null && !priorClaimRequestId.equals(root.requestId())) {
                throw conflict("IDEMPOTENCY_KEY_CONFLICT", "idempotency key payload does not match");
            }
            String denied = claimDenial(userId, root, state, generation, cancelEpoch);
            if (denied != null) {
                if ("STALE_CONTEXT".equals(denied)) {
                    throw conflict("STALE_CONTEXT", "recommendation context is stale");
                }
                return new DisplayClaimResponse(root.requestId(), false, denied);
            }
            if (root.displayClaimKey() != null) {
                if (root.displayClaimKey().equals(claimKey)) {
                    return new DisplayClaimResponse(root.requestId(), true, null);
                }
                return new DisplayClaimResponse(root.requestId(), false, "ALREADY_CLAIMED");
            }
            int claimed = jdbc.update("UPDATE location_recommendation_requests SET display_claim_key=?, "
                    + "display_claimed_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6) "
                    + "WHERE user_id=? AND request_id=? AND canonical_request_id IS NULL AND status='READY' "
                    + "AND expires_at>UTC_TIMESTAMP(6) AND display_claim_key IS NULL", claimKey.toString(), userId,
                    root.requestId().toString());
            if (claimed == 1) {
                return new DisplayClaimResponse(root.requestId(), true, null);
            }
            Request current = findByRequestId(userId, root.requestId()).orElseThrow(() -> notFound());
            String currentDenial = claimDenial(userId, current, state, generation, cancelEpoch);
            if (currentDenial != null) {
                if ("STALE_CONTEXT".equals(currentDenial)) {
                    throw conflict("STALE_CONTEXT", "recommendation context is stale");
                }
                return new DisplayClaimResponse(root.requestId(), false, currentDenial);
            }
            if (claimKey.equals(current.displayClaimKey())) {
                return new DisplayClaimResponse(root.requestId(), true, null);
            }
            return new DisplayClaimResponse(root.requestId(), false,
                    current.displayClaimKey() == null ? "NOT_READY" : "ALREADY_CLAIMED");
        });
    }

    public CancelOutcome cancel(long userId, UUID cancellationId, UUID targetRequestId,
            UUID generation, long expectedCancelEpoch) {
        return inTransaction(() -> {
            State state = lockState(userId);
            Cancellation old = findCancellation(userId, cancellationId).orElse(null);
            if (old != null) {
                if (!old.targetRequestId().equals(targetRequestId) || !old.generation().equals(generation)
                        || old.expectedEpoch() != expectedCancelEpoch) {
                    throw conflict("IDEMPOTENCY_KEY_CONFLICT", "idempotency key payload does not match");
                }
                return new CancelOutcome(
                        new CancellationResponse(old.resultCode(), old.generation(), old.resultEpoch()),
                        null);
            }
            if (!state.generation().equals(generation) || state.cancelEpoch() != expectedCancelEpoch) {
                throw conflict("STALE_CONTEXT", "recommendation context is stale");
            }

            UUID ownerRequest = state.leaseRequestId();
            UUID ledgerTarget = targetRequestId;
            jdbc.update("UPDATE location_recommendation_requests SET status='CANCELLED', error_reason='CANCELLED', "
                    + "tracks=JSON_ARRAY(), recommendation_message=NULL, "
                    + "display_claim_key=NULL, display_claimed_at=NULL, "
                    + "updated_at=UTC_TIMESTAMP(6) WHERE user_id=? AND canonical_request_id IS NULL "
                    + "AND scope_generation=? AND admitted_cancel_epoch=? AND status IN ('PROCESSING','READY')",
                    userId, generation.toString(), expectedCancelEpoch);
            if (state.leaseRequestId() != null) {
                jdbc.update("UPDATE location_recommendation_states SET lease_request_uuid=NULL, lease_region_id=NULL, "
                        + "lease_expires_at=NULL, active_request_uuid=NULL, updated_at=UTC_TIMESTAMP(6) "
                        + "WHERE user_id=? AND lease_request_uuid=?", userId, state.leaseRequestId().toString());
            }
            long resultEpoch = expectedCancelEpoch + 1;
            jdbc.update("UPDATE location_recommendation_states SET cancel_epoch=?, updated_at=UTC_TIMESTAMP(6) "
                    + "WHERE user_id=? AND scope_generation=? AND cancel_epoch=?", resultEpoch, userId,
                    generation.toString(), expectedCancelEpoch);
            jdbc.update("INSERT INTO location_recommendation_cancellations (user_id, cancellation_uuid, "
                    + "target_request_uuid, scope_generation, expected_cancel_epoch, result_cancel_epoch, "
                    + "result_code, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, 'CANCELLED', UTC_TIMESTAMP(6), "
                    + "DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 48 HOUR))", userId, cancellationId.toString(),
                    ledgerTarget.toString(), generation.toString(), expectedCancelEpoch, resultEpoch);
            if (ownerRequest != null) {
                Request owner = findByRequestId(userId, ownerRequest).map(this::resolveRoot).orElse(null);
                if (owner == null || owner.admittedCancelEpoch() != expectedCancelEpoch
                        || !owner.generation().equals(generation)) {
                    ownerRequest = null;
                }
            }
            return new CancelOutcome(new CancellationResponse("CANCELLED", generation, resultEpoch), ownerRequest);
        });
    }

    public int cleanupExpired() {
        return inTransaction(() -> {
            int deleted = jdbc.update("DELETE FROM location_recommendation_daily_issues WHERE issued_date < "
                    + "DATE(UTC_TIMESTAMP(6) + INTERVAL 9 HOUR)");
            jdbc.update("UPDATE location_recommendation_requests SET tracks=JSON_ARRAY(), recommendation_message=NULL, "
                    + "display_claim_key=NULL, display_claimed_at=NULL, updated_at=UTC_TIMESTAMP(6) "
                    + "WHERE status='READY' AND expires_at<=UTC_TIMESTAMP(6) "
                    + "AND (JSON_LENGTH(tracks)>0 OR recommendation_message IS NOT NULL)");
            jdbc.update("DELETE a FROM location_recommendation_requests a JOIN location_recommendation_requests c "
                    + "ON c.user_id=a.user_id AND c.request_id=a.canonical_request_id "
                    + "WHERE a.canonical_request_id IS NOT NULL "
                    + "AND a.created_at<=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 48 HOUR)");
            deleted += jdbc.update("DELETE c FROM location_recommendation_requests c "
                    + "LEFT JOIN location_recommendation_requests a ON a.user_id=c.user_id "
                    + "AND a.canonical_request_id=c.request_id "
                    + "WHERE c.canonical_request_id IS NULL "
                    + "AND c.created_at<=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 48 HOUR) "
                    + "AND NOT EXISTS (SELECT 1 FROM location_recommendation_daily_issues d "
                    + "WHERE d.user_id=c.user_id AND d.canonical_request_id=c.request_id) "
                    + "AND a.request_id IS NULL");
            jdbc.update("DELETE FROM location_recommendation_cancellations "
                    + "WHERE expires_at<=UTC_TIMESTAMP(6)");
            jdbc.update("UPDATE location_recommendation_states SET lease_request_uuid=NULL, lease_region_id=NULL, "
                    + "lease_expires_at=NULL, active_request_uuid=NULL, updated_at=UTC_TIMESTAMP(6) "
                    + "WHERE lease_expires_at<=UTC_TIMESTAMP(6)");
            return deleted;
        });
    }

    private AdmissionOutcome outcome(Request row, State state, long userId) {
        return new AdmissionOutcome(false, row.requestId(), row.admittedCancelEpoch(), row.generation(),
                response(row, state, userId));
    }

    private Request insertTerminalRequest(long userId, UUID requestId, UUID key, State state, long regionId,
            LocalDate date, LocationRecommendationStatus status, String reason) {
        jdbc.update("INSERT INTO location_recommendation_requests (user_id, request_id, idempotency_key, "
                + "scope_generation, region_id, scope_date, status, admitted_cancel_epoch, tracks, error_reason, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, JSON_ARRAY(), ?, UTC_TIMESTAMP(6), "
                + "UTC_TIMESTAMP(6))", userId, requestId.toString(), key.toString(), state.generation().toString(),
                regionId, date, status.name(), state.cancelEpoch(), reason);
        return findByRequestId(userId, requestId).orElseThrow();
    }

    private Request insertProcessingRequest(long userId, UUID requestId, State state, long regionId, LocalDate date) {
        jdbc.update("INSERT INTO location_recommendation_requests (user_id, request_id, idempotency_key, "
                + "scope_generation, region_id, scope_date, status, admitted_cancel_epoch, tracks, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', ?, JSON_ARRAY(), UTC_TIMESTAMP(6), "
                + "UTC_TIMESTAMP(6))", userId, requestId.toString(), requestId.toString(),
                state.generation().toString(), regionId, date, state.cancelEpoch());
        return findByRequestId(userId, requestId).orElseThrow();
    }

    private Request insertAlias(long userId, UUID aliasId, UUID key, Request root, State state,
            long regionId, LocalDate date) {
        if (root.canonicalRequestId() != null || !root.generation().equals(state.generation())
                || root.regionId() != regionId || !root.scopeDate().equals(date)) {
            throw integrityError();
        }
        jdbc.update("INSERT INTO location_recommendation_requests (user_id, request_id, idempotency_key, "
                + "canonical_request_id, scope_generation, region_id, scope_date, status, admitted_cancel_epoch, "
                + "tracks, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'JOINED', ?, JSON_ARRAY(), "
                + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", userId, aliasId.toString(),
                key.toString(), root.requestId().toString(), root.generation().toString(), regionId, date,
                root.admittedCancelEpoch());
        return findByRequestId(userId, aliasId).orElseThrow();
    }

    private Optional<Request> findReusable(long userId, long regionId, LocalDate date, State state) {
        return jdbc.query("SELECT request_id FROM location_recommendation_requests "
                + "WHERE user_id=? AND region_id=? AND scope_date=? AND scope_generation=? "
                + "AND admitted_cancel_epoch=? AND canonical_request_id IS NULL AND status IN ('PROCESSING','READY') "
                + "ORDER BY created_at DESC LIMIT 1", (row, index) -> UUID.fromString(row.getString(1)), userId,
                regionId, date, state.generation().toString(), state.cancelEpoch()).stream()
                .map(id -> findByRequestId(userId, id).orElse(null)).filter(java.util.Objects::nonNull).findFirst();
    }

    private Optional<Request> findIssuedRequest(long userId, long regionId, LocalDate date) {
        return jdbc.query("SELECT canonical_request_id FROM location_recommendation_daily_issues "
                + "WHERE user_id=? AND region_id=? AND issued_date=? LIMIT 1",
                (row, index) -> UUID.fromString(row.getString(1)), userId, regionId, date).stream()
                .map(id -> findByRequestId(userId, id).orElse(null)).filter(java.util.Objects::nonNull).findFirst();
    }

    private Optional<Request> findByIdempotencyKey(long userId, UUID key) {
        return jdbc.query("SELECT request_id FROM location_recommendation_requests "
                + "WHERE user_id=? AND idempotency_key=? LIMIT 1",
                (row, index) -> UUID.fromString(row.getString(1)), userId, key.toString()).stream()
                .map(id -> findByRequestId(userId, id).orElse(null)).filter(java.util.Objects::nonNull).findFirst();
    }

    private Optional<Request> findByRequestId(long userId, UUID id) {
        return jdbc.query("SELECT r.user_id, r.request_id, r.idempotency_key, r.canonical_request_id, "
                + "r.scope_generation, r.region_id, r.scope_date, r.status, r.admitted_cancel_epoch, "
                + "r.expires_at, r.display_claim_key, r.error_reason, r.tracks, r.recommendation_message, "
                + "CASE WHEN r.expires_at IS NULL THEN 0 ELSE GREATEST(0, "
                + "TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), r.expires_at) DIV 1000) END ttl_ms, "
                + "g.code region_code, g.name region_name, p.name sido_name "
                + "FROM location_recommendation_requests r JOIN regions g ON g.id=r.region_id "
                + "JOIN regions p ON p.id=g.parent_id WHERE r.user_id=? AND r.request_id=? LIMIT 1",
                requestRowMapper(), userId, id.toString()).stream().findFirst();
    }

    private Optional<UUID> findDisplayClaimRequest(long userId, UUID claimKey) {
        return jdbc.query("SELECT request_id FROM location_recommendation_requests "
                + "WHERE user_id=? AND canonical_request_id IS NULL AND display_claim_key=? LIMIT 1",
                (row, index) -> UUID.fromString(row.getString("request_id")), userId, claimKey.toString())
                .stream().findFirst();
    }

    private Request resolveRoot(Request request) {
        if (request.canonicalRequestId() == null) {
            if (request.status() == LocationRecommendationStatus.JOINED) {
                throw integrityError();
            }
            return request;
        }
        Request root = findByRequestId(request.userId(), request.canonicalRequestId())
                .orElseThrow(() -> integrityError());
        if (root.canonicalRequestId() != null || root.status() == LocationRecommendationStatus.JOINED
                || root.userId() != request.userId() || root.regionId() != request.regionId()
                || !root.generation().equals(request.generation()) || !root.scopeDate().equals(request.scopeDate())) {
            throw integrityError();
        }
        return root;
    }

    private RecommendationResponse response(Request request, State state, long userId) {
        LocationRecommendationStatus status = request.status();
        String reason = request.errorReason();
        List<TrackResponse> tracks = request.tracks();
        long ttl = request.ttlMillis();
        if (!request.generation().equals(state.generation())) {
            status = LocationRecommendationStatus.SUPERSEDED;
            reason = "STALE_CONTEXT";
            tracks = List.of();
            ttl = 0;
        } else if (request.admittedCancelEpoch() < state.cancelEpoch()) {
            status = LocationRecommendationStatus.CANCELLED;
            reason = "CANCELLED";
            tracks = List.of();
            ttl = 0;
        } else if (status == LocationRecommendationStatus.READY && request.ttlMillis() <= 0) {
            status = LocationRecommendationStatus.EXPIRED;
            reason = "EXPIRED";
            tracks = List.of();
            ttl = 0;
        } else if (status == LocationRecommendationStatus.FAILED && "LEASE_EXPIRED".equals(reason)) {
            status = LocationRecommendationStatus.EXPIRED;
            reason = "EXPIRED";
            tracks = List.of();
            ttl = 0;
        } else if (status == LocationRecommendationStatus.READY
                && !testRepeatEnabled && musicRecords.existsActiveRecordInRegion(userId, request.regionId())) {
            jdbc.update("UPDATE location_recommendation_requests SET status='SUPERSEDED', "
                    + "error_reason='RECORDED_REGION', tracks=JSON_ARRAY(), recommendation_message=NULL, "
                    + "display_claim_key=NULL, "
                    + "display_claimed_at=NULL, updated_at=UTC_TIMESTAMP(6) WHERE user_id=? AND request_id=? "
                    + "AND status='READY'", userId, request.requestId().toString());
            status = LocationRecommendationStatus.SUPERSEDED;
            reason = "RECORDED_REGION";
            tracks = List.of();
            ttl = 0;
        } else if (status != LocationRecommendationStatus.READY) {
            tracks = List.of();
            ttl = 0;
        }
        return new RecommendationResponse(request.requestId(), status.name(), reason,
                new RegionResponse(request.regionId(), request.regionCode(), request.regionName(), request.sidoName()),
                tracks, status == LocationRecommendationStatus.READY ? request.recommendationMessage() : null,
                request.generation(), state.cancelEpoch(), Math.min(RESULT_TTL_MILLIS, ttl));
    }

    private boolean isExpired(Request request) {
        return request.ttlMillis() <= 0;
    }

    private void expireReservation(long userId, UUID requestId) {
        jdbc.update("UPDATE location_recommendation_requests SET status='FAILED', error_reason='LEASE_EXPIRED', "
                + "tracks=JSON_ARRAY(), recommendation_message=NULL, updated_at=UTC_TIMESTAMP(6) "
                + "WHERE user_id=? AND request_id=? "
                + "AND canonical_request_id IS NULL AND status='PROCESSING'", userId, requestId.toString());
    }

    private void normalizeExpiredProcessing(long userId, Request root, State state) {
        if (root.status() == LocationRecommendationStatus.PROCESSING
                && !leaseIsActive(userId, root.requestId(), state.generation(), state.cancelEpoch())) {
            expireReservation(userId, root.requestId());
            jdbc.update("UPDATE location_recommendation_states SET lease_request_uuid=NULL, lease_region_id=NULL, "
                    + "lease_expires_at=NULL, active_request_uuid=NULL, updated_at=UTC_TIMESTAMP(6) "
                    + "WHERE user_id=? AND lease_request_uuid=? AND lease_expires_at<=UTC_TIMESTAMP(6)", userId,
                    root.requestId().toString());
        }
    }

    private String claimDenial(long userId, Request request, State state, UUID generation, long cancelEpoch) {
        if (!request.generation().equals(generation) || !request.generation().equals(state.generation())
                || request.admittedCancelEpoch() != cancelEpoch || state.cancelEpoch() != cancelEpoch) {
            return "STALE_CONTEXT";
        }
        if (request.status() == LocationRecommendationStatus.CANCELLED) {
            return "CANCELLED";
        }
        if (request.status() == LocationRecommendationStatus.FAILED
                && "LEASE_EXPIRED".equals(request.errorReason())) {
            return "EXPIRED";
        }
        if (request.status() == LocationRecommendationStatus.SUPERSEDED
                && "FEATURE_DISABLED".equals(request.errorReason())) {
            return "FEATURE_DISABLED";
        }
        if (request.status() == LocationRecommendationStatus.SUPERSEDED
                && "RECORDED_REGION".equals(request.errorReason())) {
            return "RECORDED_REGION";
        }
        if (request.status() != LocationRecommendationStatus.READY) {
            return "NOT_READY";
        }
        if (request.ttlMillis() <= 0) {
            return "EXPIRED";
        }
        if (!testRepeatEnabled && musicRecords.existsActiveRecordInRegion(userId, request.regionId())) {
            return "RECORDED_REGION";
        }
        return null;
    }

    private void insertDailyIssue(long userId, Request request, List<TrackResponse> tracks,
            String recommendationMessage, UUID generation, long cancelEpoch, long deadlineNanos) {
        requireBeforeDeadline(deadlineNanos);
        String json = serialize(tracks);
        if (!testRepeatEnabled) {
            try {
                jdbc.update("INSERT INTO location_recommendation_daily_issues (user_id, region_id, issued_date, "
                        + "canonical_request_id, created_at) VALUES (?, ?, "
                        + "DATE(UTC_TIMESTAMP(6) + INTERVAL 9 HOUR), ?, UTC_TIMESTAMP(6))",
                        userId, request.regionId(), request.requestId().toString());
            } catch (org.springframework.dao.DuplicateKeyException exception) {
                updateTerminalOwned(request, LocationRecommendationStatus.SUPERSEDED, "REGION_ISSUED_TODAY", true);
                return;
            }
        }
        requireBeforeDeadline(deadlineNanos);
        int updated = jdbc.update("UPDATE location_recommendation_requests r SET status='READY', error_reason=NULL, "
                + "issued_date=DATE(UTC_TIMESTAMP(6) + INTERVAL 9 HOUR), issued_at=UTC_TIMESTAMP(6), "
                + "expires_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE), tracks=CAST(? AS JSON), "
                + "recommendation_message=?, "
                + "updated_at=UTC_TIMESTAMP(6) WHERE r.user_id=? AND r.request_id=? "
                + "AND r.canonical_request_id IS NULL AND r.status='PROCESSING' AND EXISTS "
                + "(SELECT 1 FROM location_recommendation_states s WHERE s.user_id=r.user_id "
                + "AND s.lease_request_uuid=r.request_id AND s.scope_generation=? AND s.cancel_epoch=? "
                + "AND s.lease_expires_at>UTC_TIMESTAMP(6))", json, recommendationMessage, userId,
                request.requestId().toString(),
                generation.toString(), cancelEpoch);
        if (updated != 1 || System.nanoTime() - deadlineNanos >= 0) {
            throw new ReservationLostException();
        }
    }

    private void updateTerminalOwned(Request request, LocationRecommendationStatus status, String reason,
            boolean requireLiveLease) {
        String liveClause = requireLiveLease ? " AND s.lease_expires_at>UTC_TIMESTAMP(6)" : "";
        int updated = jdbc.update("UPDATE location_recommendation_requests r SET status=?, error_reason=?, "
                + "tracks=JSON_ARRAY(), recommendation_message=NULL, "
                + "display_claim_key=NULL, display_claimed_at=NULL, updated_at=UTC_TIMESTAMP(6) "
                + "WHERE r.user_id=? AND r.request_id=? AND r.canonical_request_id IS NULL "
                + "AND r.status='PROCESSING' AND EXISTS (SELECT 1 FROM location_recommendation_states s "
                + "WHERE s.user_id=r.user_id AND s.lease_request_uuid=r.request_id "
                + "AND s.scope_generation=r.scope_generation AND s.cancel_epoch=r.admitted_cancel_epoch"
                + liveClause + ")", status.name(), reason, request.userId(), request.requestId().toString());
        if (updated != 1) {
            throw new ReservationLostException();
        }
    }

    private void failRootIfOwned(long userId, Request request, State state, String reason) {
        if (ownsLiveLease(userId, request, state, request.generation(), request.admittedCancelEpoch())) {
            updateTerminalOwned(request, LocationRecommendationStatus.FAILED, reason, true);
            releaseLease(userId, request.requestId());
        }
    }

    private boolean ownsLiveLease(long userId, Request request, State state, UUID generation, long cancelEpoch) {
        return request.status() == LocationRecommendationStatus.PROCESSING
                && request.canonicalRequestId() == null
                && request.requestId().equals(state.leaseRequestId())
                && request.generation().equals(generation) && state.generation().equals(generation)
                && request.admittedCancelEpoch() == cancelEpoch && state.cancelEpoch() == cancelEpoch
                && leaseIsActive(userId, request.requestId(), generation, cancelEpoch);
    }

    private boolean leaseIsActive(long userId, UUID requestId, UUID generation, long cancelEpoch) {
        Boolean active = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM location_recommendation_states "
                + "WHERE user_id=? AND lease_request_uuid=? AND scope_generation=? AND cancel_epoch=? "
                + "AND lease_expires_at>UTC_TIMESTAMP(6))", Boolean.class, userId, requestId.toString(),
                generation.toString(), cancelEpoch);
        return Boolean.TRUE.equals(active);
    }

    private void releaseLease(long userId, UUID requestId) {
        jdbc.update("UPDATE location_recommendation_states SET lease_request_uuid=NULL, lease_region_id=NULL, "
                + "lease_expires_at=NULL, active_request_uuid=NULL, updated_at=UTC_TIMESTAMP(6) "
                + "WHERE user_id=? AND lease_request_uuid=?", userId, requestId.toString());
    }

    private Optional<Cancellation> findCancellation(long userId, UUID cancellationId) {
        jdbc.update("DELETE FROM location_recommendation_cancellations WHERE user_id=? AND cancellation_uuid=? "
                + "AND expires_at<=UTC_TIMESTAMP(6)", userId, cancellationId.toString());
        return jdbc.query("SELECT target_request_uuid, scope_generation, expected_cancel_epoch, "
                + "result_cancel_epoch, result_code FROM location_recommendation_cancellations "
                + "WHERE user_id=? AND cancellation_uuid=? AND expires_at>UTC_TIMESTAMP(6)",
                (row, index) -> new Cancellation(UUID.fromString(row.getString("target_request_uuid")),
                        UUID.fromString(row.getString("scope_generation")), row.getLong("expected_cancel_epoch"),
                        row.getLong("result_cancel_epoch"), row.getString("result_code")),
                userId, cancellationId.toString()).stream().findFirst();
    }

    private State lockState(long userId) {
        jdbc.update("INSERT INTO location_recommendation_states (user_id, scope_generation, cancel_epoch, updated_at) "
                + "VALUES (?, ?, 0, UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE user_id=user_id", userId,
                UUID.randomUUID().toString());
        return jdbc.query("SELECT scope_generation, cancel_epoch, lease_request_uuid, lease_region_id, "
                + "lease_expires_at, active_request_uuid, last_ai_attempt_at > "
                + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 MINUTE) cooldown_active "
                + "FROM location_recommendation_states "
                + "WHERE user_id=? FOR UPDATE", stateRowMapper(), userId).stream().findFirst()
                .orElseThrow(() -> integrityError());
    }

    private State readState(long userId) {
        return jdbc.query("SELECT scope_generation, cancel_epoch, lease_request_uuid, lease_region_id, "
                + "lease_expires_at, active_request_uuid, last_ai_attempt_at > "
                + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 MINUTE) cooldown_active "
                + "FROM location_recommendation_states "
                + "WHERE user_id=?", stateRowMapper(), userId).stream().findFirst().orElseThrow(() -> notFound());
    }

    private DbDate dbDate() {
        return jdbc.queryForObject("SELECT DATE(UTC_TIMESTAMP(6) + INTERVAL 9 HOUR) kst_date",
                (row, index) -> new DbDate(
                        row.getObject("kst_date", LocalDate.class)),
                new Object[0]);
    }

    private void requireCurrentContext(State state, UUID generation, long cancelEpoch) {
        if (!state.generation().equals(generation) || state.cancelEpoch() != cancelEpoch) {
            throw conflict("STALE_CONTEXT", "recommendation context is stale");
        }
    }

    private RequestRowMapper requestRowMapper() {
        return new RequestRowMapper();
    }

    private StateRowMapper stateRowMapper() {
        return new StateRowMapper();
    }

    private String serialize(List<TrackResponse> tracks) {
        try {
            return mapper.writeValueAsString(tracks);
        } catch (JacksonException exception) {
            throw new IllegalStateException("recommendation result serialization failed");
        }
    }

    private List<TrackResponse> tracks(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw integrityError();
            }
            List<TrackResponse> result = new ArrayList<>(root.size());
            for (JsonNode item : root) {
                result.add(new TrackResponse(item.get("trackId").stringValue(), item.get("title").stringValue(),
                        item.get("artist").stringValue(), nullableString(item, "previewUrl"),
                        nullableString(item, "artworkUrl"), nullableString(item, "storeUrl")));
            }
            return List.copyOf(result);
        } catch (JacksonException exception) {
            throw integrityError();
        }
    }

    private String nullableString(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private <T> T inTransaction(long deadlineNanos, Supplier<T> action) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        long timeoutSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos);
        if (timeoutSeconds <= 0) {
            throw timeoutException();
        }
        TransactionTemplate boundedTransaction = new TransactionTemplate(transactionManager);
        boundedTransaction.setTimeout((int) Math.min(Integer.MAX_VALUE, timeoutSeconds));
        try {
            T result = boundedTransaction.execute(status -> {
                T value = action.get();
                if (System.nanoTime() - deadlineNanos >= 0) {
                    throw timeoutException();
                }
                return value;
            });
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw timeoutException();
            }
            return result;
        } catch (LocationRecommendationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (System.nanoTime() - deadlineNanos >= 0 || isTransactionTimeout(exception)) {
                throw timeoutException();
            }
            throw exception;
        }
    }

    private boolean isTransactionTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof QueryTimeoutException || current instanceof TransactionTimedOutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private LocationRecommendationException timeoutException() {
        return new LocationRecommendationException(504, "TIMEOUT", "recommendation request timed out");
    }

    private void requireBeforeDeadline(long deadlineNanos) {
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw new ReservationLostException();
        }
    }

    private LocationRecommendationException conflict(String reason, String message) {
        return new LocationRecommendationException(409, reason, message);
    }

    private LocationRecommendationException notFound() {
        return new LocationRecommendationException(404, "NOT_FOUND", "recommendation not found");
    }

    private LocationRecommendationException integrityError() {
        return new LocationRecommendationException(500, "STATE_INTEGRITY_ERROR", "recommendation state unavailable");
    }

    private record DbDate(LocalDate kstDate) {
    }

    private record State(UUID generation, long cancelEpoch, UUID leaseRequestId, Long leaseRegionId,
            java.time.LocalDateTime leaseExpiresAt, UUID activeRequestId, boolean cooldownActive) {
    }

    private record Request(long userId, UUID requestId, UUID idempotencyKey, UUID canonicalRequestId,
            UUID generation, long regionId, LocalDate scopeDate, LocationRecommendationStatus status,
            long admittedCancelEpoch, java.time.LocalDateTime expiresAt, UUID displayClaimKey, String errorReason,
            List<TrackResponse> tracks, String recommendationMessage, long ttlMillis, String regionCode,
            String regionName, String sidoName) {
    }

    private record Cancellation(UUID targetRequestId, UUID generation, long expectedEpoch, long resultEpoch,
            String resultCode) {
    }

    public record AdmissionOutcome(boolean invokeAi, UUID canonicalRequestId, long admittedCancelEpoch,
            UUID scopeGeneration, RecommendationResponse response) {
    }

    public record CancelOutcome(CancellationResponse response, UUID ownerRequestId) {
    }

    private final class RequestRowMapper implements RowMapper<Request> {
        @Override
        public Request mapRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
            return new Request(row.getLong("user_id"), UUID.fromString(row.getString("request_id")),
                    UUID.fromString(row.getString("idempotency_key")),
                    uuidOrNull(row.getString("canonical_request_id")),
                    UUID.fromString(row.getString("scope_generation")), row.getLong("region_id"),
                    row.getObject("scope_date", LocalDate.class),
                    LocationRecommendationStatus.valueOf(row.getString("status")),
                    row.getLong("admitted_cancel_epoch"), row.getObject("expires_at", java.time.LocalDateTime.class),
                    uuidOrNull(row.getString("display_claim_key")), row.getString("error_reason"),
                    tracks(row.getString("tracks")), row.getString("recommendation_message"), row.getLong("ttl_ms"),
                    row.getString("region_code"),
                    row.getString("region_name"), row.getString("sido_name"));
        }
    }

    private UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static final class StateRowMapper implements RowMapper<State> {
        @Override
        public State mapRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
            String leaseUuid = row.getString("lease_request_uuid");
            String activeUuid = row.getString("active_request_uuid");
            return new State(UUID.fromString(row.getString("scope_generation")), row.getLong("cancel_epoch"),
                    leaseUuid == null ? null : UUID.fromString(leaseUuid),
                    row.getObject("lease_region_id", Long.class),
                    row.getObject("lease_expires_at", java.time.LocalDateTime.class),
                    activeUuid == null ? null : UUID.fromString(activeUuid), row.getBoolean("cooldown_active"));
        }
    }

    private static final class ReservationLostException extends RuntimeException {
    }
}
