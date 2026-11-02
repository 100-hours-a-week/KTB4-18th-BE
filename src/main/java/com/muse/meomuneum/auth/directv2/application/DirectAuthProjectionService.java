package com.muse.meomuneum.auth.directv2.application;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;

@Service
public class DirectAuthProjectionService {
    public record Projection(String flowId, String sid, long flowEpoch, long revision, long projectionGeneration,
            long userId, String status, long refreshGeneration, LocalDateTime idleExpiry,
            LocalDateTime absoluteExpiry) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private record Claim(long id, String flowId, String sid, String owner) {
    }

    private final JdbcTemplate jdbc;
    private final DirectAuthTransactionRunner transactions;
    private final DirectAuthFlowRepository flows;
    private final DirectAuthSessionRepository sessions;
    private final DirectAuthProjectionRepository projections;
    private final ObjectProvider<HazelcastInstance> provider;
    private final DirectAuthLegacySessionProjection legacy;

    public DirectAuthProjectionService(JdbcTemplate jdbc, DirectAuthTransactionRunner transactions,
            DirectAuthFlowRepository flows, DirectAuthSessionRepository sessions,
            DirectAuthProjectionRepository projections, ObjectProvider<HazelcastInstance> provider,
            DirectAuthLegacySessionProjection legacy) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.flows = flows;
        this.sessions = sessions;
        this.projections = projections;
        this.provider = provider;
        this.legacy = legacy;
    }

    @Scheduled(fixedDelayString = "${auth.direct-v2.projection-interval:500ms}")
    public void processPending() {
        for (int index = 0; index < 20; index++) {
            Claim claim;
            try {
                claim = transactions.run(this::claim);
            } catch (RuntimeException unavailable) {
                return;
            }
            if (claim == null) {
                return;
            }
            try {
                transactions.run(() -> {
                    deliver(claim);
                    DirectAuthProjectionRepository.requireOne(jdbc.update("""
                            UPDATE auth_direct_projection_outbox SET processed_at = CURRENT_TIMESTAMP(6),
                            claim_id = NULL, lease_expires_at = NULL WHERE event_id = ? AND claim_id = ?
                            AND processed_at IS NULL AND lease_expires_at > CURRENT_TIMESTAMP(6)
                            """, claim.id(), claim.owner()));
                    return null;
                });
            } catch (RuntimeException deferred) {
                try {
                    jdbc.update("""
                            UPDATE auth_direct_projection_outbox SET claim_id = NULL,
                            lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 SECOND)
                            WHERE event_id = ? AND claim_id = ? AND processed_at IS NULL
                            """, claim.id(), claim.owner());
                } catch (RuntimeException unavailable) {
                    return;
                }
            }
        }
    }

    private Claim claim() {
        var rows = jdbc.query("""
                SELECT o.event_id, o.flow_id, o.sid FROM auth_direct_projection_outbox o
                WHERE o.processed_at IS NULL
                AND (o.lease_expires_at IS NULL OR o.lease_expires_at <= CURRENT_TIMESTAMP(6))
                AND NOT EXISTS (SELECT 1 FROM auth_direct_projection_outbox earlier
                    WHERE earlier.sid = o.sid AND earlier.processed_at IS NULL AND earlier.event_id < o.event_id)
                ORDER BY o.event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, index) -> new Claim(row.getLong(1), row.getString(2), row.getString(3),
                UUID.randomUUID().toString()));
        if (rows.isEmpty()) {
            return null;
        }
        Claim claim = rows.getFirst();
        DirectAuthProjectionRepository.requireOne(jdbc.update("""
                UPDATE auth_direct_projection_outbox SET claim_id = ?,
                lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND)
                WHERE event_id = ? AND processed_at IS NULL
                """, claim.owner(), claim.id()));
        return claim;
    }

    private void deliver(Claim claim) {
        long generation = projections.lockControl();
        var flow = flows.lock(claim.flowId()).orElseThrow(DirectAuthFailure::unavailable);
        var session = sessions.find(claim.sid(), true).orElseThrow(DirectAuthFailure::unavailable);
        var fence = jdbc.query("""
                SELECT flow_epoch, revision, projection_generation, status FROM auth_direct_session_fences
                WHERE sid = ? AND flow_id = ? FOR UPDATE
                """, (row, index) -> new Object[]{row.getLong(1), row.getLong(2), row.getLong(3), row.getString(4)},
                claim.sid(), claim.flowId());
        if (fence.size() != 1 || (long) fence.getFirst()[2] != generation) {
            throw DirectAuthFailure.unavailable();
        }
        boolean active = session.active(flow, flows.now()) && "ACTIVE".equals(fence.getFirst()[3]);
        Projection incoming = new Projection(flow.flowId(), session.sid(), flow.epoch(), flow.revision(), generation,
                session.userId(), active ? "ACTIVE" : "REVOKED", session.refreshGeneration(),
                session.idleExpiry(), session.absoluteExpiry());
        HazelcastInstance hz = provider.getIfAvailable();
        if (hz == null || !hz.getLifecycleService().isRunning()) {
            throw DirectAuthFailure.unavailable();
        }
        IMap<String, Projection> map = hz.getMap("auth-direct-sessions-v2");
        for (int attempt = 0; attempt < 5; attempt++) {
            Projection current = map.get(session.sid());
            if (current != null && compare(current, incoming) > 0) {
                throw DirectAuthFailure.unavailable();
            }
            if (current == null
                    ? map.putIfAbsent(session.sid(), incoming) == null
                    : current.equals(incoming) || map.replace(session.sid(), current, incoming)) {
                long ttl = Math.max(1, java.time.Duration.between(flows.now(),
                        active ? session.idleExpiry() : session.absoluteExpiry().plusSeconds(86400)).getSeconds());
                if (!map.setTtl(session.sid(), ttl, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw DirectAuthFailure.unavailable();
                }
                legacy.project(flow, session, flows.now(), active);
                return;
            }
        }
        throw DirectAuthFailure.unavailable();
    }

    private int compare(Projection left, Projection right) {
        int generation = Long.compare(left.projectionGeneration(), right.projectionGeneration());
        if (generation != 0) {
            return generation;
        }
        int epoch = Long.compare(left.flowEpoch(), right.flowEpoch());
        if (epoch != 0) {
            return epoch;
        }
        int revision = Long.compare(left.revision(), right.revision());
        if (revision != 0) {
            return revision;
        }
        return "REVOKED".equals(left.status()) && "ACTIVE".equals(right.status()) ? 1 : 0;
    }
}
