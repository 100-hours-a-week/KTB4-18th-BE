package com.muse.meomuneum.auth.directv2.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFlowState;

@Repository
public class DirectAuthSessionRepository {
    private final JdbcTemplate jdbc;

    public record Session(String sid, String flowId, long epoch, long userId, String status,
            long refreshGeneration, LocalDateTime idleExpiry, LocalDateTime absoluteExpiry,
            String encryptedRefresh, String mode, String refreshHash, String accessJti, LocalDateTime accessExpiry) {
        public boolean active(DirectAuthFlowState flow, LocalDateTime now) {
            return "GENERATION_SCOPED".equals(mode) && canonical(flow, now);
        }

        public boolean canonical(DirectAuthFlowState flow, LocalDateTime now) {
            return "ACTIVE".equals(status)
                    && "AUTHENTICATED".equals(flow.lifecycle()) && sid.equals(flow.activeSessionSid())
                    && flowId.equals(flow.flowId()) && epoch == flow.epoch()
                    && now.isBefore(idleExpiry) && now.isBefore(absoluteExpiry);
        }
    }

    public DirectAuthSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Session> find(String sid, boolean lock) {
        return jdbc.query("SELECT * FROM auth_browser_sessions WHERE sid = ?" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Session(row.getString("sid"), row.getString("flow_id"), row.getLong("flow_epoch"),
                        row.getLong("user_id"), row.getString("status"), row.getLong("refresh_generation"),
                        row.getObject("idle_expires_at", LocalDateTime.class),
                        row.getObject("absolute_expires_at", LocalDateTime.class),
                        row.getString("encrypted_current_refresh_token"), row.getString("refresh_cookie_mode"),
                        row.getString("current_refresh_token_hash"), row.getString("direct_projection_access_jti"),
                        row.getObject("direct_projection_access_expires_at", LocalDateTime.class)),
                sid)
                .stream().findFirst();
    }

    public Session canonical(DirectAuthFlowState flow, LocalDateTime now, boolean lock) {
        Session session = find(flow.activeSessionSid(), lock)
                .orElseThrow(() -> new DirectAuthFailure(401, "AUTH_SESSION_INVALID"));
        if (!session.canonical(flow, now)) {
            throw new DirectAuthFailure(401, "AUTH_SESSION_INVALID");
        }
        return session;
    }

    public Session require(DirectAuthFlowState flow, LocalDateTime now, boolean lock) {
        Session session = find(flow.activeSessionSid(), lock).orElseThrow(
                () -> new DirectAuthFailure(401, "AUTH_SESSION_INVALID"));
        if (!session.active(flow, now)) {
            throw new DirectAuthFailure(401, "AUTH_SESSION_INVALID");
        }
        return session;
    }

    public void requireCapacity(long userId, int maximumPerUser, int maximumGlobal) {
        var counts = jdbc.queryForObject("""
                SELECT COUNT(*) AS total, SUM(CASE WHEN s.user_id = ? THEN 1 ELSE 0 END) AS per_user
                FROM auth_browser_sessions s JOIN auth_browser_flows f ON f.flow_id = s.flow_id
                WHERE s.status = 'ACTIVE' AND f.lifecycle = 'AUTHENTICATED'
                AND f.active_session_sid = s.sid AND f.flow_epoch = s.flow_epoch
                AND s.idle_expires_at > CURRENT_TIMESTAMP(6) AND s.absolute_expires_at > CURRENT_TIMESTAMP(6)
                """, (row, index) -> new long[]{row.getLong("total"), row.getLong("per_user")}, userId);
        if (counts == null || counts[0] >= maximumGlobal || counts[1] >= maximumPerUser) {
            throw DirectAuthFailure.unavailable();
        }
    }

    public void insert(String sid, DirectAuthFlowState flow, long epoch, long userId, LocalDateTime idle,
            LocalDateTime absolute, String hash, String encrypted, String accessJti, LocalDateTime accessExpiry) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                INSERT INTO auth_browser_sessions
                (sid, flow_id, flow_epoch, user_id, status, idle_expires_at, absolute_expires_at,
                 current_refresh_token_hash, encrypted_current_refresh_token, refresh_generation,
                 refresh_cookie_mode, direct_projection_access_jti, direct_projection_access_expires_at,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 1, 'GENERATION_SCOPED', ?, ?,
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, sid, flow.flowId(), epoch, userId, idle, absolute, hash, encrypted, accessJti, accessExpiry));
    }

    public void revoke(Session session) {
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_browser_sessions SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6),
                current_refresh_token_hash = NULL, encrypted_current_refresh_token = NULL,
                updated_at = CURRENT_TIMESTAMP(6)
                WHERE sid = ? AND flow_id = ? AND flow_epoch = ? AND status = 'ACTIVE'
                """, session.sid(), session.flowId(), session.epoch()));
    }
}
