package com.muse.meomuneum.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.exception.AuthFlowException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.AccessSessionAuthority;

@Service
public class AuthFlowService implements AccessSessionAuthority {
    public static final String FLOW_COOKIE_NAME = "MEO_AUTH_FLOW";
    public static final String FLOW_COOKIE_PATH = "/api/v1/auth";

    public enum SessionDecision {
        ACTIVE, REJECTED, UNAVAILABLE
    }

    public record FlowSnapshot(String flowId, long flowEpoch, long revision, String lifecycle,
            boolean authenticated, String activeSessionSid, String rebootstrapReason) {
    }

    public record BootstrapResult(FlowSnapshot flow, String cookieValue, long maxAgeSeconds,
            boolean newlyCreated) {
    }

    public record LogoutReceipt(String operationId, boolean committed, long flowEpoch, long revision,
            Instant completedAt) {
    }

    public record RefreshReceipt(String operationId, boolean committed, long flowEpoch, long revision,
            long resultGeneration) {
    }

    public record RefreshSessionProjection(Long userId, String sid, String refreshToken,
            Instant idleExpiry, Instant absoluteExpiry) {
    }

    @FunctionalInterface
    public interface FlowMutation<T> {
        T apply(FlowSnapshot flow);
    }

    private record FlowRow(String flowId, String cookieHash, long epoch, long revision, String lifecycle,
            String sessionSid, Instant cookieExpiresAt) {
        FlowSnapshot snapshot(String reason) {
            return new FlowSnapshot(flowId, epoch, revision, lifecycle, "AUTHENTICATED".equals(lifecycle),
                    sessionSid, reason);
        }
    }

    private record SessionToRevoke(String sid, String cookieMode, LocalDateTime absoluteExpiry) {
    }

    private final JdbcTemplate jdbc;
    private final DirectAuthProjectionRepository projections;
    private final TransactionTemplate transactions;
    private final AuthStateProperties properties;
    private final boolean secureCookie;
    private final String sameSite;
    private final boolean mutationGateOpen;
    private final int bootstrapRequestsPerMinute;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthFlowService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            AuthStateProperties properties, @Value("${auth.cookie.secure:true}") boolean secureCookie,
            @Value("${auth.cookie.same-site:Lax}") String sameSite,
            @Value("${auth.mutation-gate-open:false}") boolean mutationGateOpen,
            @Value("${auth.flow.bootstrap-requests-per-minute:120}") int bootstrapRequestsPerMinute,
            JwtProperties jwtProperties, DirectAuthProjectionRepository projections) {
        this.jdbc = jdbc;
        this.projections = projections;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
        this.mutationGateOpen = mutationGateOpen;
        this.bootstrapRequestsPerMinute = bootstrapRequestsPerMinute > 0 ? bootstrapRequestsPerMinute : 120;
        String hmacKey = properties.hmacKey();
        if (hmacKey == null || hmacKey.getBytes(StandardCharsets.UTF_8).length < 32
                || hmacKey.equals(jwtProperties.secret())) {
            throw new IllegalStateException("AUTH_STATE_HMAC_KEY must be a distinct shared key of at least 256 bits");
        }
    }

    public BootstrapResult bootstrap(HttpServletRequest request, HttpHeaders headers) {
        String presented = cookieValue(request, FLOW_COOKIE_NAME);
        try {
            if (requiresBootstrap(presented)) {
                enforceBootstrapRateLimit(request.getRemoteAddr());
            }
            BootstrapResult result = transactions.execute(status -> {
                FlowRow row = presented == null ? null : findFlow(hashCookie(presented), true);
                boolean created = row == null || !row.cookieExpiresAt().isAfter(Instant.now());
                String cookie = presented;
                String reason = "EXISTING";
                if (created) {
                    if (presented == null) {
                        reason = "CREATED";
                    } else if (row == null) {
                        reason = "INVALID_COOKIE";
                    } else {
                        reason = "EXPIRED_COOKIE";
                        retireExpiredFlow(row);
                    }
                    cookie = createFlowCookie();
                    row = insertFlow(cookie);
                }
                FlowSnapshot snapshot = row.snapshot(reason);
                long maxAge = maxAge(row.cookieExpiresAt());
                addFlowCookie(headers, cookie, maxAge);
                setTupleHeaders(headers, snapshot);
                return new BootstrapResult(snapshot, cookie, maxAge, created);
            });
            if (result == null) {
                throw new AuthStateUnavailableException();
            }
            return result;
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public FlowSnapshot currentFlow(HttpServletRequest request) {
        String cookie = cookieValue(request, FLOW_COOKIE_NAME);
        String flowId = request.getHeader("X-Auth-Flow-Id");
        String epoch = request.getHeader("X-Auth-Flow-Epoch");
        if (cookie == null || flowId == null || epoch == null) {
            throw new AuthFlowException("AUTH_FLOW_REQUIRED");
        }
        try {
            FlowSnapshot snapshot = transactions.execute(status -> {
                FlowRow row = findFlow(hashCookie(cookie), false);
                if (row == null) {
                    throw new AuthFlowException("AUTH_FLOW_COOKIE_INVALID");
                }
                validateFlowTuple(row, flowId, epoch);
                if (!row.cookieExpiresAt().isAfter(Instant.now())) {
                    throw new AuthFlowException("AUTH_FLOW_COOKIE_INVALID");
                }
                return row.snapshot("EXISTING");
            });
            if (snapshot == null) {
                throw new AuthStateUnavailableException();
            }
            return snapshot;
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public <T> T withLockedFlow(HttpServletRequest request, FlowMutation<T> mutation) {
        assertMutationGateOpen();
        String cookie = cookieValue(request, FLOW_COOKIE_NAME);
        String flowId = request.getHeader("X-Auth-Flow-Id");
        String epoch = request.getHeader("X-Auth-Flow-Epoch");
        if (cookie == null || flowId == null || epoch == null) {
            throw new AuthFlowException("AUTH_FLOW_REQUIRED");
        }
        try {
            FlowSnapshot snapshot = transactions.execute(status -> {
                FlowRow row = findFlow(hashCookie(cookie), true);
                if (row == null) {
                    throw new AuthFlowException("AUTH_FLOW_COOKIE_INVALID");
                }
                validateFlowTuple(row, flowId, epoch);
                if (!row.cookieExpiresAt().isAfter(Instant.now())) {
                    throw new AuthFlowException("AUTH_FLOW_COOKIE_INVALID");
                }
                return row.snapshot("EXISTING");
            });
            if (snapshot == null) {
                throw new AuthStateUnavailableException();
            }
            return mutation.apply(snapshot);
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void assertFlowSnapshotCurrent(FlowSnapshot expected) {
        try {
            FlowSnapshot current = transactions.execute(status -> {
                FlowRow row = findFlowById(expected.flowId(), false);
                return row == null ? null : row.snapshot("EXISTING");
            });
            if (current == null || current.flowEpoch() != expected.flowEpoch()
                    || current.revision() != expected.revision()) {
                throw new AuthFlowException("AUTH_FLOW_EPOCH_STALE");
            }
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void enqueueRejectedSessionCleanup(String flowId, long flowEpoch, String sid) {
        try {
            transactions.executeWithoutResult(status -> {
                FlowRow current = findFlowById(flowId, false);
                long epoch = current == null ? flowEpoch : current.epoch();
                long revision = current == null ? 0 : current.revision();
                insertOutbox(flowId, epoch, revision, "SESSION_REVOKED_STALE_LOGIN", sid, Instant.now());
            });
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public boolean shouldApplyOutboxAction(String flowId, long eventEpoch, long eventRevision,
            String eventKind, String targetId) {
        if (targetId == null || targetId.isBlank() || !eventKind.startsWith("SESSION_REVOKED_")) {
            return false;
        }
        try {
            FlowRow flow = findFlowById(flowId, false);
            if (flow != null && (flow.epoch() < eventEpoch
                    || (flow.epoch() == eventEpoch && flow.revision() < eventRevision))) {
                return false;
            }
            if (eventKind.startsWith("SESSION_REVOKED_")) {
                return !Boolean.TRUE.equals(queryExists("""
                        SELECT 1 FROM auth_browser_sessions s
                        JOIN auth_browser_flows f ON f.flow_id = s.flow_id
                        WHERE s.sid = ? AND s.status = 'ACTIVE'
                          AND s.flow_id = ? AND s.flow_epoch = f.flow_epoch
                          AND f.active_session_sid = s.sid
                        """, targetId, flowId));
            }
            return true;
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public Long sessionOwnerUserId(String sid) {
        try {
            return jdbc.query("SELECT user_id FROM auth_browser_sessions WHERE sid = ?",
                    resultSet -> resultSet.next() ? resultSet.getLong(1) : null, sid);
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void writeTupleHeaders(HttpHeaders headers, FlowSnapshot flow) {
        setTupleHeaders(headers, flow);
    }

    public LogoutReceipt commitLogout(HttpServletRequest request, HttpHeaders headers, String operationId) {
        assertMutationGateOpen();
        requireUuid(operationId, "INVALID_OPERATION_ID");
        String cookie = cookieValue(request, FLOW_COOKIE_NAME);
        String flowId = request.getHeader("X-Auth-Flow-Id");
        String epochValue = request.getHeader("X-Auth-Flow-Epoch");
        if (cookie == null || flowId == null || epochValue == null) {
            throw new AuthFlowException("AUTH_FLOW_REQUIRED");
        }
        try {
            LogoutReceipt receipt = transactions.execute(status -> {
                FlowRow row = findFlow(hashCookie(cookie), true);
                if (row == null) {
                    throw new AuthFlowException("AUTH_FLOW_COOKIE_INVALID");
                }
                if (!row.flowId().equals(flowId)) {
                    throw new AuthFlowException("AUTH_FLOW_ID_MISMATCH");
                }
                long requestedEpoch = parseEpoch(epochValue);
                String requestHash = hmac("logout-request:" + flowId + ":" + requestedEpoch + ":" + operationId);
                LogoutReceipt saved = findLogoutReceipt(flowId, operationId, requestHash);
                if (saved != null) {
                    if (row.epoch() != saved.flowEpoch() || !"ANONYMOUS".equals(row.lifecycle())
                            || row.revision() != saved.revision() || row.sessionSid() != null) {
                        throw new AuthFlowException("AUTH_FLOW_EPOCH_STALE");
                    }
                    setTupleHeaders(headers, row.snapshot("EXISTING"));
                    return saved;
                }
                if (requestedEpoch != row.epoch()) {
                    throw new AuthFlowException("AUTH_FLOW_EPOCH_STALE");
                }
                Instant now = Instant.now();
                long nextEpoch = row.epoch() + 1;
                long nextRevision = row.revision() + 1;
                String previousSid = row.sessionSid();
                if (previousSid != null) {
                    jdbc.update("""
                            UPDATE auth_browser_sessions SET status = 'REVOKED', current_refresh_token_hash = NULL,
                                encrypted_current_refresh_token = NULL, revoked_at = ?, updated_at = ?
                            WHERE sid = ? AND status = 'ACTIVE'
                            """, timestamp(now), timestamp(now), previousSid);
                    insertOutbox(flowId, nextEpoch, nextRevision, "SESSION_REVOKED_LOGOUT", previousSid, now);
                }
                jdbc.update("""
                        UPDATE auth_browser_flows
                        SET flow_epoch = ?, revision = ?, lifecycle = 'ANONYMOUS',
                            active_session_sid = NULL, updated_at = ?
                        WHERE flow_id = ? AND flow_epoch = ?
                        """, nextEpoch, nextRevision, timestamp(now), flowId, row.epoch());
                jdbc.update("""
                        INSERT INTO auth_flow_operations
                            (flow_id, operation_kind, operation_id, request_hash, started_epoch,
                             result_epoch, result_revision, committed_at)
                        VALUES (?, 'LOGOUT', ?, ?, ?, ?, ?, ?)
                        """, flowId, operationId, requestHash, row.epoch(), nextEpoch, nextRevision, timestamp(now));
                FlowSnapshot updated = new FlowSnapshot(flowId, nextEpoch, nextRevision, "ANONYMOUS", false,
                        null, "EXISTING");
                setTupleHeaders(headers, updated);
                return new LogoutReceipt(operationId, true, nextEpoch, nextRevision, now);
            });
            if (receipt == null) {
                throw new AuthStateUnavailableException();
            }
            return receipt;
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public SessionDecision checkSession(Long userId, String sid) {
        try {
            Boolean active = transactions.execute(status -> {
                String flowId;
                try {
                    flowId = jdbc.queryForObject("SELECT flow_id FROM auth_browser_sessions "
                            + "WHERE sid = ? AND user_id = ?", String.class, sid, userId);
                } catch (EmptyResultDataAccessException missing) {
                    return false;
                }
                FlowRow flow = findFlowById(flowId, false);
                SessionRow session = findSession(sid, false);
                Instant now = Instant.now();
                return flow != null && session != null && "ACTIVE".equals(session.status())
                        && "AUTHENTICATED".equals(flow.lifecycle()) && sid.equals(flow.sessionSid())
                        && session.flowEpoch() == flow.epoch() && session.idleExpiry().isAfter(now)
                        && session.absoluteExpiry().isAfter(now);
            });
            return Boolean.TRUE.equals(active) ? SessionDecision.ACTIVE : SessionDecision.REJECTED;
        } catch (DataAccessException exception) {
            return SessionDecision.UNAVAILABLE;
        }
    }

    @Override
    public AccessSessionAuthority.Decision verifySession(Long userId, String sid) {
        SessionDecision decision = checkSession(userId, sid);
        if (decision == SessionDecision.ACTIVE) {
            return AccessSessionAuthority.Decision.ACTIVE;
        }
        return decision == SessionDecision.REJECTED
                ? AccessSessionAuthority.Decision.REJECTED
                : AccessSessionAuthority.Decision.UNAVAILABLE;
    }

    public void assertActiveSession(Long userId, String sid) {
        SessionDecision decision = checkSession(userId, sid);
        if (decision == SessionDecision.UNAVAILABLE) {
            throw new AuthStateUnavailableException();
        }
        if (decision != SessionDecision.ACTIVE) {
            throw new AuthFlowException("AUTH_SESSION_INVALID");
        }
    }

    public boolean isConsumedRefreshToken(String sid, String tokenHash) {
        try {
            Boolean consumed = queryExists("""
                    SELECT 1 FROM auth_session_refresh_token_history
                    WHERE sid = ? AND token_hash = ? AND expires_at > CURRENT_TIMESTAMP(6)
                    """, sid, tokenHash);
            return Boolean.TRUE.equals(consumed);
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void recordConsumedRefreshToken(String sid, String tokenHash, Instant absoluteExpiry) {
        try {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO auth_session_refresh_token_history (token_hash, sid, consumed_at, expires_at)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE expires_at = GREATEST(expires_at, VALUES(expires_at))
                    """, tokenHash, sid, timestamp(now), timestamp(absoluteExpiry));
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void assertCurrentRefreshToken(FlowSnapshot expected, String operationId, String presentedToken) {
        requireUuid(operationId, "INVALID_OPERATION_ID");
        if (presentedToken == null || presentedToken.isBlank()) {
            throw new AuthFlowException("AUTH_REFRESH_STALE");
        }
        String presentedHash = hmac("refresh:" + presentedToken);
        String requestHash = refreshRequestHash(expected, operationId, presentedHash);
        try {
            Boolean allowed = transactions.execute(status -> {
                FlowRow flow = findFlowById(expected.flowId(), false);
                if (flow == null || flow.epoch() != expected.flowEpoch()
                        || !java.util.Objects.equals(flow.sessionSid(), expected.activeSessionSid())) {
                    return false;
                }
                String savedHash = jdbc.query("""
                        SELECT request_hash FROM auth_flow_operations
                        WHERE flow_id = ? AND operation_kind = 'REFRESH' AND operation_id = ?
                        """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                        flow.flowId(), operationId);
                if (savedHash != null) {
                    if (!savedHash.equals(requestHash)) {
                        throw new AuthFlowException("AUTH_OPERATION_ID_REUSED");
                    }
                    return false;
                }
                SessionRow session = findSession(flow.sessionSid(), false);
                String currentHash = jdbc.query("""
                        SELECT current_refresh_token_hash FROM auth_browser_sessions
                        WHERE sid = ? AND status = 'ACTIVE' AND flow_epoch = ?
                        """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                        flow.sessionSid(), flow.epoch());
                return session != null && "ACTIVE".equals(session.status())
                        && session.flowEpoch() == flow.epoch() && presentedHash.equals(currentHash);
            });
            if (!Boolean.TRUE.equals(allowed)) {
                throw new AuthFlowException("AUTH_REFRESH_STALE");
            }
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public FlowSnapshot commitRefresh(HttpServletRequest request, FlowSnapshot expected, String operationId,
            String presentedToken, String refreshToken, RefreshReplayCipher replayCipher, HttpHeaders headers) {
        String cookie = cookieValue(request, FLOW_COOKIE_NAME);
        String flowId = request.getHeader("X-Auth-Flow-Id");
        String epoch = request.getHeader("X-Auth-Flow-Epoch");
        if (cookie == null || flowId == null || epoch == null || refreshToken == null || refreshToken.isBlank()
                || presentedToken == null || presentedToken.isBlank()) {
            throw new AuthFlowException("AUTH_FLOW_REQUIRED");
        }
        requireUuid(operationId, "INVALID_OPERATION_ID");
        String presentedHash = hmac("refresh:" + presentedToken);
        String requestHash = refreshRequestHash(expected, operationId, presentedHash);
        try {
            FlowSnapshot refreshed = transactions.execute(status -> {
                FlowRow flow = findFlow(hashCookie(cookie), true);
                if (flow == null || !flow.flowId().equals(expected.flowId())) {
                    throw new AuthFlowException("AUTH_FLOW_COOKIE_INVALID");
                }
                validateFlowTuple(flow, flowId, epoch);
                String savedHash = jdbc.query("""
                        SELECT request_hash FROM auth_flow_operations
                        WHERE flow_id = ? AND operation_kind = 'REFRESH' AND operation_id = ?
                        """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                        flow.flowId(), operationId);
                if (savedHash != null) {
                    if (!savedHash.equals(requestHash)) {
                        throw new AuthFlowException("AUTH_OPERATION_ID_REUSED");
                    }
                    SessionRow savedSession = findSession(flow.sessionSid(), true);
                    String currentHash = currentRefreshHash(flow.sessionSid());
                    if (savedSession == null || !"ACTIVE".equals(savedSession.status())
                            || !hmac("refresh:" + refreshToken).equals(currentHash)) {
                        throw new AuthFlowException("AUTH_REFRESH_STALE");
                    }
                    FlowSnapshot replayed = flow.snapshot("EXISTING");
                    setTupleHeaders(headers, replayed);
                    return replayed;
                }
                if (flow.epoch() != expected.flowEpoch() || !"AUTHENTICATED".equals(flow.lifecycle())
                        || !java.util.Objects.equals(flow.sessionSid(), expected.activeSessionSid())) {
                    throw new AuthFlowException("AUTH_FLOW_EPOCH_STALE");
                }
                SessionRow session = findSession(flow.sessionSid(), true);
                Instant now = Instant.now();
                if (session == null || !"ACTIVE".equals(session.status())
                        || session.flowEpoch() != flow.epoch()
                        || !session.idleExpiry().isAfter(now) || !session.absoluteExpiry().isAfter(now)) {
                    throw new AuthFlowException("AUTH_SESSION_INVALID");
                }
                String currentHash = currentRefreshHash(flow.sessionSid());
                if (!presentedHash.equals(currentHash)) {
                    throw new AuthFlowException("AUTH_REFRESH_STALE");
                }
                Instant idleExpiry = min(now.plusSeconds(properties.sessionIdleSeconds()), session.absoluteExpiry());
                int sessionUpdated = jdbc.update("""
                        UPDATE auth_browser_sessions SET current_refresh_token_hash = ?,
                            encrypted_current_refresh_token = ?, refresh_generation = refresh_generation + 1,
                            idle_expires_at = ?, updated_at = ?
                        WHERE sid = ? AND status = 'ACTIVE' AND flow_epoch = ?
                          AND current_refresh_token_hash = ? AND refresh_generation = ?
                        """, hmac("refresh:" + refreshToken), replayCipher.encrypt(refreshToken),
                        timestamp(idleExpiry), timestamp(now), flow.sessionSid(), flow.epoch(), presentedHash,
                        session.refreshGeneration());
                if (sessionUpdated != 1) {
                    throw new AuthFlowException("AUTH_REFRESH_STALE");
                }
                int updated = jdbc.update("""
                        UPDATE auth_browser_flows SET cookie_expires_at = LEAST(?, ?),
                            revision = revision + 1, updated_at = ?
                        WHERE flow_id = ? AND flow_epoch = ? AND active_session_sid = ?
                        """, timestamp(idleExpiry), timestamp(session.absoluteExpiry()), timestamp(now),
                        flow.flowId(), flow.epoch(), flow.sessionSid());
                if (updated != 1) {
                    throw new AuthFlowException("AUTH_FLOW_EPOCH_STALE");
                }
                jdbc.update("""
                        INSERT INTO auth_flow_operations
                            (flow_id, operation_kind, operation_id, request_hash, started_epoch,
                             result_epoch, result_revision, result_generation,
                             encrypted_result_refresh_token, committed_at)
                        VALUES (?, 'REFRESH', ?, ?, ?, ?, ?, ?, ?, ?)
                        """, flow.flowId(), operationId, requestHash, flow.epoch(), flow.epoch(),
                        flow.revision() + 1, session.refreshGeneration() + 1, replayCipher.encrypt(refreshToken),
                        timestamp(now));
                FlowSnapshot snapshot = readFlowById(flow.flowId());
                addFlowCookie(headers, cookie, maxAge(idleExpiry));
                addRefreshCookie(headers, refreshToken, idleExpiry);
                setTupleHeaders(headers, snapshot);
                return snapshot;
            });
            if (refreshed == null) {
                throw new AuthStateUnavailableException();
            }
            return refreshed;
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public RefreshSessionProjection currentRefreshSession(String sid, Long userId,
            RefreshReplayCipher replayCipher) {
        try {
            RefreshSessionProjection projection = jdbc.query("""
                    SELECT session.user_id, session.sid, session.current_refresh_token_hash,
                           session.encrypted_current_refresh_token, session.idle_expires_at,
                           session.absolute_expires_at
                    FROM auth_browser_sessions session
                    JOIN auth_browser_flows flow ON flow.active_session_sid = session.sid
                    WHERE session.sid = ? AND session.user_id = ? AND session.status = 'ACTIVE'
                      AND session.flow_epoch = flow.flow_epoch
                      AND session.idle_expires_at > CURRENT_TIMESTAMP(6)
                      AND session.absolute_expires_at > CURRENT_TIMESTAMP(6)
                    """, resultSet -> {
                if (!resultSet.next()) {
                    return null;
                }
                String encryptedToken = resultSet.getString("encrypted_current_refresh_token");
                if (encryptedToken == null) {
                    return null;
                }
                String refreshToken = replayCipher.decrypt(encryptedToken);
                if (!hmac("refresh:" + refreshToken).equals(
                        resultSet.getString("current_refresh_token_hash"))) {
                    return null;
                }
                return new RefreshSessionProjection(resultSet.getLong("user_id"), resultSet.getString("sid"),
                        refreshToken, resultSet.getTimestamp("idle_expires_at").toInstant(),
                        resultSet.getTimestamp("absolute_expires_at").toInstant());
            }, sid, userId);
            return projection;
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public long refreshGeneration(FlowSnapshot flow) {
        if (!flow.authenticated() || flow.activeSessionSid() == null) {
            return 0;
        }
        try {
            Long generation = jdbc.query("""
                    SELECT refresh_generation FROM auth_browser_sessions
                    WHERE sid = ? AND flow_id = ? AND flow_epoch = ? AND status = 'ACTIVE'
                    """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                    flow.activeSessionSid(), flow.flowId(), flow.flowEpoch());
            return generation == null ? 0 : generation;
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public String replayCommittedRefresh(FlowSnapshot expected, String operationId, String presentedToken,
            RefreshReplayCipher replayCipher) {
        requireUuid(operationId, "INVALID_OPERATION_ID");
        if (presentedToken == null || presentedToken.isBlank()) {
            return null;
        }
        String requestHash = refreshRequestHash(expected, operationId, hmac("refresh:" + presentedToken));
        try {
            return transactions.execute(status -> jdbc.query("""
                    SELECT operation.request_hash, operation.result_epoch, operation.result_generation,
                           operation.encrypted_result_refresh_token, session.status, session.flow_epoch,
                           session.refresh_generation, session.current_refresh_token_hash,
                           flow.flow_epoch AS current_flow_epoch
                    FROM auth_flow_operations operation
                    JOIN auth_browser_sessions session ON session.flow_id = operation.flow_id
                    JOIN auth_browser_flows flow ON flow.active_session_sid = session.sid
                    WHERE operation.flow_id = ? AND operation.operation_kind = 'REFRESH'
                      AND operation.operation_id = ? AND session.sid = ?
                    FOR UPDATE
                    """, resultSet -> {
                if (!resultSet.next()) {
                    return null;
                }
                String savedHash = resultSet.getString("request_hash");
                if (!requestHash.equals(savedHash)) {
                    throw new AuthFlowException("AUTH_OPERATION_ID_REUSED");
                }
                long resultEpoch = resultSet.getLong("result_epoch");
                long resultGeneration = resultSet.getLong("result_generation");
                if (!"ACTIVE".equals(resultSet.getString("status"))
                        || resultSet.getLong("flow_epoch") != expected.flowEpoch()
                        || resultSet.getLong("current_flow_epoch") != expected.flowEpoch()
                        || resultEpoch != expected.flowEpoch()
                        || resultSet.getLong("refresh_generation") != resultGeneration) {
                    return null;
                }
                String encryptedToken = resultSet.getString("encrypted_result_refresh_token");
                if (encryptedToken == null) {
                    return null;
                }
                String refreshToken = replayCipher.decrypt(encryptedToken);
                return hmac("refresh:" + refreshToken).equals(
                        resultSet.getString("current_refresh_token_hash")) ? refreshToken : null;
            }, expected.flowId(), operationId, expected.activeSessionSid()));
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public RefreshReceipt lastRefreshReceipt(String flowId) {
        try {
            return jdbc.query("""
                    SELECT operation_id, result_epoch, result_revision, result_generation
                    FROM auth_flow_operations
                    WHERE flow_id = ? AND operation_kind = 'REFRESH'
                    ORDER BY committed_at DESC LIMIT 1
                    """, resultSet -> resultSet.next()
                    ? new RefreshReceipt(resultSet.getString("operation_id"), true,
                            resultSet.getLong("result_epoch"), resultSet.getLong("result_revision"),
                            resultSet.getLong("result_generation"))
                    : null, flowId);
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void repairCurrentRefreshCookie(HttpServletRequest request, BootstrapResult bootstrap,
            RefreshReplayCipher replayCipher, HttpHeaders headers) {
        if (!bootstrap.flow().authenticated() || bootstrap.flow().activeSessionSid() == null) {
            return;
        }
        try {
            var tokenRow = jdbc.query("""
                    SELECT current_refresh_token_hash, encrypted_current_refresh_token, idle_expires_at,
                           absolute_expires_at
                    FROM auth_browser_sessions
                    WHERE sid = ? AND flow_id = ? AND flow_epoch = ? AND status = 'ACTIVE'
                    """, resultSet -> resultSet.next()
                    ? new CurrentRefreshToken(resultSet.getString("current_refresh_token_hash"),
                            resultSet.getString("encrypted_current_refresh_token"),
                            resultSet.getTimestamp("idle_expires_at").toInstant(),
                            resultSet.getTimestamp("absolute_expires_at").toInstant())
                    : null, bootstrap.flow().activeSessionSid(), bootstrap.flow().flowId(),
                    bootstrap.flow().flowEpoch());
            if (tokenRow == null || tokenRow.encryptedToken() == null || tokenRow.tokenHash() == null) {
                return;
            }
            String presented = cookieValue(request, "refresh_token");
            boolean repair = presented == null;
            if (presented != null && !tokenRow.tokenHash().equals(hmac("refresh:" + presented))) {
                repair = isConsumedRefreshToken(bootstrap.flow().activeSessionSid(),
                        hmac("refresh:" + presented));
            }
            if (repair) {
                String current = replayCipher.decrypt(tokenRow.encryptedToken());
                long maxAge = Math.max(0, min(tokenRow.idleExpiry(), tokenRow.absoluteExpiry()).getEpochSecond()
                        - Instant.now().getEpochSecond());
                headers.add(HttpHeaders.SET_COOKIE, ResponseCookie.from("refresh_token", current).httpOnly(true)
                        .secure(secureCookie).sameSite(sameSite).path(FLOW_COOKIE_PATH)
                        .maxAge(maxAge).build().toString());
            }
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void extendIdleExpiry(String sid, Instant requestedIdleExpiry) {
        try {
            transactions.executeWithoutResult(status -> jdbc.update("""
                    UPDATE auth_browser_sessions
                    SET idle_expires_at = LEAST(?, absolute_expires_at), updated_at = ?
                    WHERE sid = ? AND status = 'ACTIVE'
                    """, timestamp(requestedIdleExpiry), timestamp(Instant.now()), sid));
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void revokeSession(String sid, Long expectedUserId, String reason) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> {
                String flowId;
                try {
                    flowId = jdbc.queryForObject("SELECT flow_id FROM auth_browser_sessions WHERE sid = ?",
                            String.class, sid);
                } catch (EmptyResultDataAccessException missing) {
                    return;
                }
                FlowRow flow = findFlowById(flowId, true);
                SessionRow session = findSession(sid, true);
                if (session == null || (expectedUserId != null && !expectedUserId.equals(session.userId()))) {
                    return;
                }
                if (!"ACTIVE".equals(session.status())) {
                    return;
                }
                Instant now = Instant.now();
                jdbc.update("""
                        UPDATE auth_browser_sessions SET status = 'REVOKED', current_refresh_token_hash = NULL,
                                encrypted_current_refresh_token = NULL, revoked_at = ?, updated_at = ?
                        WHERE sid = ? AND status = 'ACTIVE'
                        """, timestamp(now), timestamp(now), sid);
                boolean current = sid.equals(flow.sessionSid());
                long nextEpoch = current ? flow.epoch() + 1 : flow.epoch();
                long nextRevision = current ? flow.revision() + 1 : flow.revision();
                if (current) {
                    jdbc.update("""
                            UPDATE auth_browser_flows
                            SET flow_epoch = ?, revision = ?, lifecycle = 'ANONYMOUS',
                                active_session_sid = NULL, updated_at = ?
                            WHERE flow_id = ? AND flow_epoch = ?
                            """, nextEpoch, nextRevision, timestamp(now), flow.flowId(), flow.epoch());
                }
                insertOutbox(flow.flowId(), nextEpoch, nextRevision,
                        "SESSION_REVOKED_" + sanitizeReason(reason), sid, now);
            });
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public int reapExpiredSessions(int limit) {
        List<String> expiredSids;
        try {
            expiredSids = jdbc.query("""
                    SELECT sid FROM auth_browser_sessions
                    WHERE status = 'ACTIVE'
                      AND (idle_expires_at <= CURRENT_TIMESTAMP(6)
                           OR absolute_expires_at <= CURRENT_TIMESTAMP(6))
                    ORDER BY flow_id, sid LIMIT ?
                    """, (resultSet, rowNum) -> resultSet.getString("sid"), Math.max(1, limit));
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
        int revoked = 0;
        for (String sid : expiredSids) {
            if (revokeExpiredSession(sid)) {
                revoked++;
            }
        }
        return revoked;
    }

    public int reapExpiredAnonymousFlows(int limit) {
        List<String> flowIds;
        try {
            flowIds = jdbc.query("""
                    SELECT flow_id FROM auth_browser_flows
                    WHERE lifecycle = 'ANONYMOUS' AND cookie_expires_at <= CURRENT_TIMESTAMP(6)
                    ORDER BY flow_id LIMIT ?
                    """, (resultSet, rowNum) -> resultSet.getString("flow_id"), Math.max(1, limit));
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
        int retired = 0;
        for (String flowId : flowIds) {
            try {
                Boolean changed = transactions.execute(status -> {
                    FlowRow flow = findFlowById(flowId, true);
                    if (flow == null || !"ANONYMOUS".equals(flow.lifecycle())
                            || flow.cookieExpiresAt().isAfter(Instant.now())) {
                        return false;
                    }
                    retireExpiredFlow(flow);
                    return true;
                });
                if (Boolean.TRUE.equals(changed)) {
                    retired++;
                }
            } catch (DataAccessException exception) {
                throw new AuthStateUnavailableException(exception);
            }
        }
        return retired;
    }

    public int reapRetainedFlowData(int limit) {
        Instant now = Instant.now();
        long retentionSeconds = Math.max(properties.sessionAbsoluteSeconds(),
                properties.idempotencyIdleHorizonSeconds());
        Instant cutoff = now.minusSeconds(retentionSeconds);
        List<String> flowIds;
        try {
            flowIds = jdbc.query("""
                    SELECT f.flow_id FROM auth_browser_flows f
                    WHERE f.lifecycle = 'RETIRED' AND f.active_session_sid IS NULL AND f.updated_at <= ?
                      AND NOT EXISTS (SELECT 1 FROM auth_browser_sessions s
                          WHERE s.flow_id = f.flow_id AND s.status = 'ACTIVE')
                      AND NOT EXISTS (SELECT 1 FROM auth_flow_outbox o
                          WHERE o.flow_id = f.flow_id AND o.processed_at IS NULL)
                    ORDER BY f.updated_at, f.flow_id LIMIT ?
                    """, (resultSet, rowNum) -> resultSet.getString(1), timestamp(cutoff), Math.max(1, limit));
            int deleted = 0;
            for (String flowId : flowIds) {
                deleted += jdbc.update("""
                        DELETE f FROM auth_browser_flows f
                        WHERE f.flow_id = ? AND f.lifecycle = 'RETIRED' AND f.active_session_sid IS NULL
                          AND f.updated_at <= ?
                          AND NOT EXISTS (SELECT 1 FROM auth_browser_sessions s
                              WHERE s.flow_id = f.flow_id AND s.status = 'ACTIVE')
                          AND NOT EXISTS (SELECT 1 FROM auth_flow_outbox o
                              WHERE o.flow_id = f.flow_id AND o.processed_at IS NULL)
                        """, flowId, timestamp(cutoff));
            }
            jdbc.update("DELETE FROM auth_flow_bootstrap_rate_limits WHERE window_started_at < ? LIMIT ?",
                    timestamp(now.minusSeconds(600)), Math.max(1, limit));
            return deleted;
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    private boolean requiresBootstrap(String presentedCookie) {
        if (presentedCookie == null) {
            return true;
        }
        FlowRow existing = findFlow(hashCookie(presentedCookie), false);
        return existing == null || !existing.cookieExpiresAt().isAfter(Instant.now());
    }

    private void enforceBootstrapRateLimit(String remoteAddress) {
        Instant now = Instant.now();
        Instant window = Instant.ofEpochSecond(now.getEpochSecond() / 60 * 60);
        String addressHash = hmac("auth-flow-bootstrap-address:" +
                (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress));
        Integer requests = transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO auth_flow_bootstrap_rate_limits
                        (address_hash, window_started_at, request_count, updated_at)
                    VALUES (?, ?, 1, ?)
                    ON DUPLICATE KEY UPDATE request_count = request_count + 1, updated_at = VALUES(updated_at)
                    """, addressHash, timestamp(window), timestamp(now));
            return jdbc.queryForObject("""
                    SELECT request_count FROM auth_flow_bootstrap_rate_limits
                    WHERE address_hash = ? AND window_started_at = ?
                    """, Integer.class, addressHash, timestamp(window));
        });
        if (requests == null || requests > bootstrapRequestsPerMinute) {
            throw new AuthFlowException("AUTH_FLOW_BOOTSTRAP_RATE_LIMITED");
        }
    }

    private boolean revokeExpiredSession(String sid) {
        try {
            Boolean revoked = transactions.execute(status -> {
                String flowId;
                try {
                    flowId = jdbc.queryForObject("SELECT flow_id FROM auth_browser_sessions WHERE sid = ?",
                            String.class, sid);
                } catch (EmptyResultDataAccessException missing) {
                    return false;
                }
                FlowRow flow = findFlowById(flowId, true);
                SessionRow session = findSession(sid, true);
                Instant now = Instant.now();
                if (session == null || !"ACTIVE".equals(session.status())
                        || session.idleExpiry().isAfter(now) && session.absoluteExpiry().isAfter(now)) {
                    return false;
                }
                jdbc.update("""
                        UPDATE auth_browser_sessions SET status = 'REVOKED', current_refresh_token_hash = NULL,
                            encrypted_current_refresh_token = NULL, revoked_at = ?, updated_at = ?
                        WHERE sid = ? AND status = 'ACTIVE'
                        """, timestamp(now), timestamp(now), sid);
                boolean current = sid.equals(flow.sessionSid());
                long nextEpoch = current ? flow.epoch() + 1 : flow.epoch();
                long nextRevision = current ? flow.revision() + 1 : flow.revision();
                if (current) {
                    jdbc.update("""
                            UPDATE auth_browser_flows SET flow_epoch = ?, revision = ?, lifecycle = 'ANONYMOUS',
                                active_session_sid = NULL, updated_at = ?
                            WHERE flow_id = ? AND flow_epoch = ? AND active_session_sid = ?
                            """, nextEpoch, nextRevision, timestamp(now), flowId, flow.epoch(), sid);
                }
                insertOutbox(flowId, nextEpoch, nextRevision, "SESSION_REVOKED_EXPIRED", sid, now);
                return true;
            });
            return Boolean.TRUE.equals(revoked);
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public List<String> revokeAllSessions(Long userId, String reason) {
        try {
            List<String> revokedSids = transactions.execute(status -> {
                List<String> revoked = new ArrayList<>();
                var flowIds = jdbc.queryForList("""
                        SELECT DISTINCT flow_id FROM auth_browser_sessions
                        WHERE user_id = ? AND status = 'ACTIVE' ORDER BY flow_id
                        """, String.class, userId);
                if (flowIds.isEmpty()) {
                    return List.of();
                }
                long projectionGeneration = projections.lockGenerationForRevocation();
                for (String flowId : flowIds) {
                    FlowRow flow = findFlowById(flowId, true);
                    var activeSessions = jdbc.query("""
                            SELECT sid, refresh_cookie_mode, absolute_expires_at FROM auth_browser_sessions
                            WHERE flow_id = ? AND user_id = ? AND status = 'ACTIVE' ORDER BY sid FOR UPDATE
                            """, (row, index) -> new SessionToRevoke(row.getString("sid"),
                            row.getString("refresh_cookie_mode"),
                            row.getObject("absolute_expires_at", LocalDateTime.class)), flowId, userId);
                    if (activeSessions.isEmpty()) {
                        continue;
                    }
                    Instant now = Instant.now();
                    jdbc.update("""
                            UPDATE auth_browser_sessions SET status = 'REVOKED', current_refresh_token_hash = NULL,
                                encrypted_current_refresh_token = NULL, revoked_at = ?, updated_at = ?
                            WHERE flow_id = ? AND user_id = ? AND status = 'ACTIVE'
                            """, timestamp(now), timestamp(now), flowId, userId);
                    long nextEpoch = flow.epoch() + 1;
                    long nextRevision = flow.revision() + 1;
                    jdbc.update("""
                            UPDATE auth_browser_flows
                            SET flow_epoch = ?, revision = ?, lifecycle = 'ANONYMOUS',
                                active_session_sid = NULL, updated_at = ?
                            WHERE flow_id = ? AND flow_epoch = ? AND active_session_sid IN
                                (SELECT sid FROM auth_browser_sessions WHERE flow_id = ? AND user_id = ?)
                            """, nextEpoch, nextRevision, timestamp(now), flowId, flow.epoch(), flowId, userId);
                    String eventKind = "SESSION_REVOKED_" + sanitizeReason(reason);
                    for (SessionToRevoke session : activeSessions) {
                        if ("GENERATION_SCOPED".equals(session.cookieMode())) {
                            projections.fenceRevocation(flowId, session.sid(), nextEpoch, nextRevision,
                                    projectionGeneration, session.absoluteExpiry().plusDays(1), eventKind);
                        } else {
                            insertOutbox(flowId, nextEpoch, nextRevision, eventKind, session.sid(), now);
                        }
                        revoked.add(session.sid());
                    }
                }
                return List.copyOf(revoked);
            });
            return revokedSids == null ? List.of() : revokedSids;
        } catch (DataAccessException | DirectAuthFailure exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void assertCurrentSession(FlowSnapshot flow) {
        try {
            Boolean active = queryExists("""
                    SELECT 1 FROM auth_browser_sessions
                    WHERE sid = ? AND flow_id = ? AND flow_epoch = ? AND status = 'ACTIVE'
                      AND idle_expires_at > CURRENT_TIMESTAMP(6)
                      AND absolute_expires_at > CURRENT_TIMESTAMP(6)
                    FOR SHARE
                    """, flow.activeSessionSid(), flow.flowId(), flow.flowEpoch());
            if (!Boolean.TRUE.equals(active)) {
                throw new AuthFlowException("AUTH_SESSION_INVALID");
            }
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    public void expireAnonymousCookies(HttpHeaders headers) {
        expireRefreshCookie(headers);
    }

    public void expireRefreshCookie(HttpHeaders headers) {
        headers.add(HttpHeaders.SET_COOKIE, ResponseCookie.from("refresh_token", "").httpOnly(true)
                .secure(secureCookie).sameSite(sameSite).path(FLOW_COOKIE_PATH).maxAge(0).build().toString());
    }

    public void addCurrentFlowCookie(HttpHeaders headers, BootstrapResult result) {
        addFlowCookie(headers, result.cookieValue(), result.maxAgeSeconds());
    }

    public LogoutReceipt lastLogoutReceipt(String flowId) {
        try {
            return jdbc.query("""
                    SELECT operation_id, result_epoch, result_revision, committed_at
                    FROM auth_flow_operations
                    WHERE flow_id = ? AND operation_kind = 'LOGOUT'
                    ORDER BY committed_at DESC LIMIT 1
                    """, resultSet -> resultSet.next()
                    ? new LogoutReceipt(resultSet.getString("operation_id"), true,
                            resultSet.getLong("result_epoch"), resultSet.getLong("result_revision"),
                            resultSet.getTimestamp("committed_at").toInstant())
                    : null, flowId);
        } catch (DataAccessException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    private LogoutReceipt findLogoutReceipt(String flowId, String operationId, String requestHash) {
        try {
            return jdbc.queryForObject("""
                    SELECT request_hash, started_epoch, result_epoch, result_revision, committed_at
                    FROM auth_flow_operations
                    WHERE flow_id = ? AND operation_kind = 'LOGOUT' AND operation_id = ?
                    """, (rs, rowNum) -> {
                if (!requestHash.equals(rs.getString("request_hash"))) {
                    throw new AuthFlowException("AUTH_OPERATION_ID_REUSED");
                }
                Instant completedAt = rs.getTimestamp("committed_at").toInstant();
                return new LogoutReceipt(operationId, true, rs.getLong("result_epoch"),
                        rs.getLong("result_revision"), completedAt);
            }, flowId, operationId);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private FlowRow insertFlow(String cookie) {
        String flowId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.sessionIdleSeconds());
        String hash = hashCookie(cookie);
        jdbc.update("""
                INSERT INTO auth_browser_flows
                    (flow_id, flow_cookie_hash, flow_epoch, revision, lifecycle, active_session_sid,
                     cookie_expires_at, created_at, updated_at)
                VALUES (?, ?, 1, 1, 'ANONYMOUS', NULL, ?, ?, ?)
                """, flowId, hash, timestamp(expiresAt), timestamp(now), timestamp(now));
        return new FlowRow(flowId, hash, 1, 1, "ANONYMOUS", null, expiresAt);
    }

    private FlowRow findFlow(String cookieHash, boolean lock) {
        String sql = """
                SELECT flow_id, flow_cookie_hash, flow_epoch, revision, lifecycle,
                       active_session_sid, cookie_expires_at
                FROM auth_browser_flows WHERE flow_cookie_hash = ?
                """ + (lock ? " FOR UPDATE" : " FOR SHARE");
        try {
            return jdbc.queryForObject(sql, (rs, rowNum) -> new FlowRow(rs.getString("flow_id"),
                    rs.getString("flow_cookie_hash"), rs.getLong("flow_epoch"), rs.getLong("revision"),
                    rs.getString("lifecycle"), rs.getString("active_session_sid"),
                    rs.getTimestamp("cookie_expires_at").toInstant()), cookieHash);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private FlowRow findFlowById(String flowId, boolean lock) {
        String sql = """
                SELECT flow_id, flow_cookie_hash, flow_epoch, revision, lifecycle,
                       active_session_sid, cookie_expires_at
                FROM auth_browser_flows WHERE flow_id = ?
                """ + (lock ? " FOR UPDATE" : " FOR SHARE");
        try {
            return jdbc.queryForObject(sql, (rs, rowNum) -> new FlowRow(rs.getString("flow_id"),
                    rs.getString("flow_cookie_hash"), rs.getLong("flow_epoch"), rs.getLong("revision"),
                    rs.getString("lifecycle"), rs.getString("active_session_sid"),
                    rs.getTimestamp("cookie_expires_at").toInstant()), flowId);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private SessionRow findSession(String sid, boolean lock) {
        String sql = "SELECT user_id, status, flow_epoch, refresh_generation, idle_expires_at, absolute_expires_at "
                + "FROM auth_browser_sessions WHERE sid = ?"
                + (lock ? " FOR UPDATE" : " FOR SHARE");
        try {
            return jdbc.queryForObject(sql,
                    (rs, rowNum) -> new SessionRow(rs.getLong("user_id"), rs.getString("status"),
                            rs.getLong("flow_epoch"), rs.getLong("refresh_generation"),
                            rs.getTimestamp("idle_expires_at").toInstant(),
                            rs.getTimestamp("absolute_expires_at").toInstant()),
                    sid);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private String sanitizeReason(String reason) {
        if (reason == null || !reason.matches("[A-Z_]{1,24}")) {
            return "OTHER";
        }
        return reason;
    }

    private FlowSnapshot readFlowById(String flowId) {
        return jdbc.queryForObject("""
                SELECT flow_id, flow_epoch, revision, lifecycle, active_session_sid
                FROM auth_browser_flows WHERE flow_id = ?
                """, (rs, rowNum) -> new FlowSnapshot(rs.getString("flow_id"), rs.getLong("flow_epoch"),
                rs.getLong("revision"), rs.getString("lifecycle"),
                "AUTHENTICATED".equals(rs.getString("lifecycle")),
                rs.getString("active_session_sid"), "EXISTING"), flowId);
    }

    private void retireExpiredFlow(FlowRow row) {
        Instant now = Instant.now();
        if (row.sessionSid() != null) {
            jdbc.update("""
                    UPDATE auth_browser_sessions SET status = 'REVOKED', current_refresh_token_hash = NULL,
                                encrypted_current_refresh_token = NULL, revoked_at = ?, updated_at = ?
                    WHERE sid = ? AND status = 'ACTIVE'
                    """, timestamp(now), timestamp(now), row.sessionSid());
            insertOutbox(row.flowId(), row.epoch() + 1, row.revision() + 1,
                    "SESSION_REVOKED_FLOW_EXPIRED", row.sessionSid(), now);
        }
        jdbc.update("""
                UPDATE auth_browser_flows
                SET flow_epoch = flow_epoch + 1, revision = revision + 1, lifecycle = 'RETIRED',
                    active_session_sid = NULL, updated_at = ?
                WHERE flow_id = ?
                """, timestamp(now), row.flowId());
    }

    private void insertOutbox(String flowId, long epoch, long revision, String kind, String target, Instant now) {
        jdbc.update("""
                INSERT INTO auth_flow_outbox (flow_id, flow_epoch, revision, event_kind, target_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, flowId, epoch, revision, kind, target, timestamp(now));
    }

    private void validateFlowTuple(FlowRow row, String flowId, String epochValue) {
        if (!row.flowId().equals(flowId)) {
            throw new AuthFlowException("AUTH_FLOW_ID_MISMATCH");
        }
        if (parseEpoch(epochValue) != row.epoch()) {
            throw new AuthFlowException("AUTH_FLOW_EPOCH_STALE");
        }
    }

    private long parseEpoch(String value) {
        try {
            long epoch = Long.parseLong(value);
            if (epoch <= 0) {
                throw new NumberFormatException();
            }
            return epoch;
        } catch (NumberFormatException exception) {
            throw new AuthFlowException("AUTH_FLOW_REQUIRED");
        }
    }

    private void requireUuid(String value, String code) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new AuthFlowException(code);
        }
    }

    private String createFlowCookie() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashCookie(String cookie) {
        return hmac("auth-flow-cookie:" + cookie);
    }

    private String hmac(String value) {
        String key = properties.hmacKey();
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new AuthStateUnavailableException();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new AuthStateUnavailableException(exception);
        }
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void addFlowCookie(HttpHeaders headers, String value, long maxAgeSeconds) {
        headers.add(HttpHeaders.SET_COOKIE, ResponseCookie.from(FLOW_COOKIE_NAME, value).httpOnly(true)
                .secure(secureCookie).sameSite(sameSite).path(FLOW_COOKIE_PATH)
                .maxAge(Math.max(0, maxAgeSeconds)).build().toString());
    }

    private void addRefreshCookie(HttpHeaders headers, String value, Instant expiresAt) {
        if (value == null || value.isBlank()) {
            return;
        }
        headers.add(HttpHeaders.SET_COOKIE, ResponseCookie.from("refresh_token", value).httpOnly(true)
                .secure(secureCookie).sameSite(sameSite).path(FLOW_COOKIE_PATH)
                .maxAge(Math.max(0, expiresAt.getEpochSecond() - Instant.now().getEpochSecond()))
                .build().toString());
    }

    private String refreshCookieValue(HttpHeaders headers) {
        return headers.getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("refresh_token="))
                .map(value -> value.substring("refresh_token=".length()).split(";", 2)[0])
                .findFirst().orElse(null);
    }

    private void setTupleHeaders(HttpHeaders headers, FlowSnapshot flow) {
        headers.set("X-Auth-Flow-Id", flow.flowId());
        headers.set("X-Auth-Flow-Epoch", Long.toString(flow.flowEpoch()));
        headers.set("X-Auth-Flow-Revision", Long.toString(flow.revision()));
    }

    private String currentRefreshHash(String sid) {
        if (sid == null) {
            return null;
        }
        return jdbc.query("SELECT current_refresh_token_hash FROM auth_browser_sessions WHERE sid = ?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null, sid);
    }

    private String refreshRequestHash(FlowSnapshot flow, String operationId, String presentedTokenHash) {
        return hmac("refresh-request:" + flow.flowId() + ":" + flow.flowEpoch() + ":" + operationId + ":"
                + presentedTokenHash);
    }

    private void assertMutationGateOpen() {
        if (!mutationGateOpen) {
            throw new AuthStateUnavailableException();
        }
    }

    private boolean queryExists(String sql, Object... arguments) {
        Boolean exists = jdbc.query(sql, (ResultSetExtractor<Boolean>) resultSet -> resultSet.next(), arguments);
        return Boolean.TRUE.equals(exists);
    }

    private long maxAge(Instant expiry) {
        return Math.max(0, expiry.getEpochSecond() - Instant.now().getEpochSecond());
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private record SessionRow(Long userId, String status, long flowEpoch, long refreshGeneration, Instant idleExpiry,
            Instant absoluteExpiry) {
    }

    private record CurrentRefreshToken(String tokenHash, String encryptedToken, Instant idleExpiry,
            Instant absoluteExpiry) {
    }
}
