package com.muse.meomuneum.auth.queue;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent;

/**
 * Materializes committed database revocations in Hazelcast. Database checks remain authoritative, so this worker is
 * cleanup and cannot reopen a revoked flow.
 */
@Component
@ConditionalOnProperty(prefix = "auth.state.hazelcast", name = "enabled", havingValue = "true")
public class AuthFlowOutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(AuthFlowOutboxProcessor.class);
    private static final int MAX_BATCH_SIZE = 50;

    private record Event(long eventId, String flowId, long flowEpoch, long revision, String eventKind,
            String targetId, String claimId) {
    }

    private static final RowMapper<Event> EVENT_MAPPER = (ResultSet rs, int rowNum) -> new Event(
            rs.getLong("event_id"), rs.getString("flow_id"), rs.getLong("flow_epoch"), rs.getLong("revision"),
            rs.getString("event_kind"), rs.getString("target_id"), rs.getString("claim_id"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final HazelcastAuthStateStore store;
    private final AuthFlowService flowService;
    private final ApplicationEventPublisher events;

    @Autowired
    public AuthFlowOutboxProcessor(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            HazelcastAuthStateStore store, AuthFlowService flowService, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.store = store;
        this.flowService = flowService;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${auth.flow.outbox-interval:250ms}")
    public void processPending() {
        try {
            for (int processed = 0; processed < MAX_BATCH_SIZE; processed++) {
                Event event = transactions.execute(status -> claimOne());
                if (event == null) {
                    return;
                }
                try {
                    if (flowService.shouldApplyOutboxAction(event.flowId(), event.flowEpoch(), event.revision(),
                            event.eventKind(), event.targetId())) {
                        Long userId = materialize(event);
                        if (event.eventKind().startsWith("SESSION_REVOKED_")) {
                            if (userId == null) {
                                userId = flowService.sessionOwnerUserId(event.targetId());
                            }
                            if (userId != null) {
                                events.publishEvent(new ChatSessionLogoutEvent(userId, event.targetId()));
                            }
                        }
                    }
                    acknowledge(event);
                } catch (RuntimeException failure) {
                    releaseClaim(event);
                    throw failure;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Auth flow outbox materialization deferred type={}", failure.getClass().getSimpleName());
        }
    }

    private Event claimOne() {
        Event event = nextPendingEvent();
        if (event == null) {
            return null;
        }
        String claimId = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE auth_flow_outbox
                SET attempts = attempts + 1, claim_id = ?, claimed_until = ?
                WHERE event_id = ? AND processed_at IS NULL
                """, claimId, java.sql.Timestamp.from(Instant.now().plusSeconds(30)), event.eventId());
        return new Event(event.eventId(), event.flowId(), event.flowEpoch(), event.revision(), event.eventKind(),
                event.targetId(), claimId);
    }

    private Event nextPendingEvent() {
        try {
            return jdbc.queryForObject("""
                    SELECT event_id, flow_id, flow_epoch, revision, event_kind, target_id, claim_id
                    FROM auth_flow_outbox
                    WHERE processed_at IS NULL
                      AND (claimed_until IS NULL OR claimed_until < CURRENT_TIMESTAMP(6))
                    ORDER BY event_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, EVENT_MAPPER);
        } catch (org.springframework.dao.EmptyResultDataAccessException empty) {
            return null;
        }
    }

    private Long materialize(Event event) {
        if ("QUEUE_CLEANUP".equals(event.eventKind()) || "FLOW_RETIRED".equals(event.eventKind())) {
            store.cancelTicketById(event.targetId());
            return null;
        }
        if (event.eventKind().startsWith("SESSION_REVOKED_")) {
            var revoked = store.revokeSession(event.targetId(), null);
            return revoked == null ? null : revoked.userId();
        }
        return null;
    }

    private void acknowledge(Event event) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE auth_flow_outbox SET processed_at = ?, claim_id = NULL, claimed_until = NULL
                WHERE event_id = ? AND processed_at IS NULL AND claim_id = ?
                """, java.sql.Timestamp.from(Instant.now()), event.eventId(), event.claimId()));
    }

    private void releaseClaim(Event event) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE auth_flow_outbox SET claim_id = NULL, claimed_until = NULL
                WHERE event_id = ? AND processed_at IS NULL AND claim_id = ?
                """, event.eventId(), event.claimId()));
    }
}
