package com.muse.meomuneum.auth.directv2.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.model.DirectAuthNonceTicket;
import com.muse.meomuneum.auth.directv2.security.DirectAuthRestoreSafety;

@Repository
public class DirectAuthProjectionRepository implements DirectAuthNonceAllocator.RestoreSafetyGate {
    private final JdbcTemplate jdbc;
    private final DirectAuthRestoreSafety safety;

    public DirectAuthProjectionRepository(JdbcTemplate jdbc) {
        this(jdbc, new DirectAuthRestoreSafety(new StandardEnvironment()));
    }

    @Autowired
    public DirectAuthProjectionRepository(JdbcTemplate jdbc, DirectAuthRestoreSafety safety) {
        this.jdbc = jdbc;
        this.safety = safety;
    }

    public boolean generationMode() {
        String mode = jdbc.queryForObject(
                "SELECT cookie_mode FROM auth_direct_projection_control WHERE singleton_id = 1", String.class);
        if (!"LEGACY".equals(mode) && !"GENERATION_SCOPED".equals(mode)) {
            throw DirectAuthFailure.unavailable();
        }
        return "GENERATION_SCOPED".equals(mode);
    }

    public long lockControl() {
        return lockControl(false);
    }

    public long lockGenerationForRevocation() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A transaction is required");
        }
        // Revocation must remain possible when projection delivery is paused.
        Long generation = jdbc.queryForObject("""
                SELECT projection_generation FROM auth_direct_projection_control WHERE singleton_id = 1 FOR SHARE
                """, Long.class);
        if (generation == null || generation <= 0) {
            throw DirectAuthFailure.unavailable();
        }
        return generation;
    }

    public long lockAdmission(int capacity) {
        long generation = lockControl(true);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM auth_direct_session_fences", Long.class);
        if (count == null || count >= capacity) {
            throw DirectAuthFailure.unavailable();
        }
        return generation;
    }

    private long lockControl(boolean exclusive) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A transaction is required");
        }
        var rows = jdbc.query("""
                SELECT projection_generation, readiness, restore_verified, replay_key_id FROM
                auth_direct_projection_control
                WHERE singleton_id = 1 AND cookie_mode = 'GENERATION_SCOPED'
                """ + (exclusive ? " FOR UPDATE" : " FOR SHARE"),
                (row, index) -> new Object[]{row.getLong(1), row.getString(2), row.getBoolean(3), row.getString(4)});
        if (rows.size() != 1 || rows.getFirst()[3] == null) {
            throw DirectAuthFailure.unavailable();
        }
        safety.require((long) rows.getFirst()[0], (String) rows.getFirst()[1], (boolean) rows.getFirst()[2]);
        return (long) rows.getFirst()[0];
    }

    @Override
    public void requireSafe(String keyId) {
        lockSafe(keyId);
    }

    @Override
    public long lockSafe(String keyId) {
        long generation = lockControl();
        String current = jdbc.queryForObject("SELECT replay_key_id FROM auth_direct_projection_control "
                + "WHERE singleton_id = 1 FOR SHARE", String.class);
        if (current == null || !MessageDigest.isEqual(keyId.getBytes(StandardCharsets.US_ASCII),
                current.getBytes(StandardCharsets.US_ASCII))) {
            throw DirectAuthFailure.unavailable();
        }
        return generation;
    }

    public long checkTicket(DirectAuthNonceTicket ticket) {
        return lockTicket(ticket, false);
    }

    public long lockTicket(DirectAuthNonceTicket ticket) {
        return lockTicket(ticket, true);
    }

    private long lockTicket(DirectAuthNonceTicket ticket, boolean authorize) {
        long generation = lockSafe(ticket.keyId());
        var pins = jdbc.queryForList("SELECT key_id FROM auth_replay_key_pin WHERE singleton_id = 1 FOR SHARE",
                String.class);
        if (pins.size() != 1 || !ticket.keyId().equals(pins.getFirst())) {
            throw DirectAuthFailure.unavailable();
        }
        ticket.check(generation, pins.getFirst());
        if (authorize) {
            ticket.authorize(generation, pins.getFirst());
        }
        return generation;
    }

    public void fence(String flowId, String sid, long epoch, long revision, long generation, String status,
            java.time.LocalDateTime retainedUntil) {
        fence(flowId, sid, epoch, revision, generation, status, retainedUntil, "SESSION_REVOKED_LOGOUT");
    }

    public void fenceRevocation(String flowId, String sid, long epoch, long revision, long generation,
            java.time.LocalDateTime retainedUntil, String eventKind) {
        fence(flowId, sid, epoch, revision, generation, "REVOKED", retainedUntil, eventKind);
    }

    private void fence(String flowId, String sid, long epoch, long revision, long generation, String status,
            java.time.LocalDateTime retainedUntil, String eventKind) {
        var current = jdbc.query("SELECT sid FROM auth_direct_session_fences WHERE sid = ? FOR UPDATE",
                (row, index) -> row.getString(1), sid);
        int changed;
        if (current.isEmpty()) {
            changed = jdbc.update("""
                    INSERT INTO auth_direct_session_fences
                    (sid, flow_id, flow_epoch, revision, projection_generation, status, retained_until, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                    """, sid, flowId, epoch, revision, generation, status, retainedUntil);
        } else {
            changed = jdbc.update("""
                    UPDATE auth_direct_session_fences SET flow_epoch = ?, revision = ?,
                    projection_generation = ?, status = ?, retained_until = GREATEST(COALESCE(retained_until, ?), ?),
                    updated_at = CURRENT_TIMESTAMP(6)
                    WHERE sid = ? AND flow_id = ? AND flow_epoch <= ? AND revision < ?
                    """, epoch, revision, generation, status, retainedUntil, retainedUntil, sid, flowId, epoch,
                    revision);
        }
        requireOne(changed);
        requireOne(jdbc.update("""
                INSERT INTO auth_direct_projection_outbox (flow_id, sid, created_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6))
                """, flowId, sid));
        if ("REVOKED".equals(status)) {
            requireOne(jdbc.update("""
                    INSERT INTO auth_flow_outbox (flow_id, flow_epoch, revision, event_kind, target_id, created_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                    """, flowId, epoch, revision, eventKind, sid));
        }
    }

    public static void requireOne(int rows) {
        if (rows != 1) {
            throw DirectAuthFailure.unavailable();
        }
    }
}
