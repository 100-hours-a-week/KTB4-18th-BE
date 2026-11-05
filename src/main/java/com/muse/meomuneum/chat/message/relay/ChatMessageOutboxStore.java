package com.muse.meomuneum.chat.message.relay;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.chat.control.ChatControlEvent;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;

/** DB claim and completion only. Call Redis after claim() returns and releases its transaction. */
public final class ChatMessageOutboxStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ChatParticipationService participation;
    private final String environment;

    public ChatMessageOutboxStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ChatParticipationService participation, String environment) {
        this.jdbc = jdbc;
        this.participation = participation;
        this.environment = environment;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(2);
        jdbc.setQueryTimeout(1);
    }

    public record Claim(ChatRedisEvent event, String token, int attempts, List<String> eventIds) {
        public Claim {
            eventIds = List.copyOf(eventIds);
            if (eventIds.isEmpty() || eventIds.size() > 100 || !eventIds.contains(event.eventId())) {
                throw new IllegalArgumentException("invalid outbox claim batch");
            }
        }
    }

    public List<Long> candidateRooms() {
        return jdbc.queryForList("""
                SELECT DISTINCT o.room_id FROM chat_message_outbox o JOIN chat_room_runtime r
                    ON r.environment=o.environment AND r.room_id=o.room_id
                WHERE o.environment=? AND o.epoch=? AND o.status='PENDING' AND o.next_attempt_at<=UTC_TIMESTAMP(6)
                    AND (r.dispatch_until IS NULL OR r.dispatch_until<=UTC_TIMESTAMP(6))
                ORDER BY o.room_id LIMIT 100
                """, Long.class, environment, participation.epoch());
    }

    public Claim claim(long room) {
        if (participation.transitionBusy()) {
            return null; // No claim or completion is manufactured; the durable head remains pending.
        }
        return transaction(room, true, () -> {
            var held = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM chat_room_runtime WHERE environment=? AND room_id=?
                        AND dispatch_until>UTC_TIMESTAMP(6)
                    """, Integer.class, environment, room);
            if (held != 0) {
                return null;
            }
            var rows = jdbc.queryForList("""
                    SELECT *,next_attempt_at<=UTC_TIMESTAMP(6) AS due,expires_at>UTC_TIMESTAMP(6) AS unexpired
                    FROM chat_message_outbox WHERE environment=? AND epoch=? AND room_id=? AND status='PENDING'
                    ORDER BY room_seq,event_id LIMIT 100 FOR UPDATE
                    """, environment, participation.epoch(), room);
            if (rows.isEmpty()) {
                return null;
            }
            var row = rows.getFirst();
            String id = (String) row.get("event_id");
            if (((Number) row.get("unexpired")).intValue() == 0) {
                jdbc.update("UPDATE chat_message_outbox SET status='EXPIRED',claim_token=NULL WHERE event_id=?", id);
                return null;
            }
            if (((Number) row.get("due")).intValue() == 0) {
                return null; // A failed head is not bypassed by a later event in this room.
            }
            var covered = new ArrayList<String>();
            covered.add(id);
            int attempts = ((Number) row.get("attempts")).intValue();
            if ("CHAT_PRESENCE_CHANGED".equals(row.get("event_type"))) {
                // A fresh snapshot represents consecutive presence hints. Never cross a message,
                // terminal event, delayed retry or expiry; all covered rows stay PENDING until publish confirmation.
                for (var next : rows.subList(1, rows.size())) {
                    if (!"CHAT_PRESENCE_CHANGED".equals(next.get("event_type"))
                            || ((Number) next.get("due")).intValue() == 0
                            || ((Number) next.get("unexpired")).intValue() == 0) {
                        break;
                    }
                    row = next;
                    covered.add((String) next.get("event_id"));
                    attempts = Math.max(attempts, ((Number) next.get("attempts")).intValue());
                }
                id = (String) row.get("event_id");
            }
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    UPDATE chat_room_runtime SET dispatch_token=?,dispatch_until=TIMESTAMPADD(SECOND,5,UTC_TIMESTAMP(6))
                    WHERE environment=? AND room_id=?
                    """, token, environment, room);
            new NamedParameterJdbcTemplate(jdbc).update("""
                    UPDATE chat_message_outbox SET claim_token=:token
                    WHERE environment=:environment AND event_id IN (:ids) AND status='PENDING'
                    """, Map.of("token", token, "environment", environment, "ids", paddedIds(covered)));
            Object at = row.get("occurred_at");
            LocalDateTime time = at instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) at;
            ChatRedisEvent event = "CHAT_MESSAGE_CREATED".equals(row.get("event_type"))
                    ? new ChatMessageRedisEvent(id, (String) row.get("producer_id"),
                            ((Number) row.get("epoch")).longValue(), room, ((Number) row.get("room_seq")).longValue(),
                            ((Number) row.get("message_id")).longValue(), time.toInstant(ZoneOffset.UTC))
                    : ChatControlEvent.decode((String) row.get("event_body"));
            return new Claim(event, token, attempts, covered);
        });
    }

    public boolean publishable(Claim claim) {
        return transaction(claim.event.roomId(), () -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_message_outbox x JOIN chat_room_runtime r
                    ON r.environment=x.environment AND r.room_id=x.room_id
                WHERE x.event_id=? AND x.claim_token=? AND x.status='PENDING' AND x.expires_at>UTC_TIMESTAMP(6)
                    AND r.dispatch_token=? AND r.dispatch_until>UTC_TIMESTAMP(6)
                """, Integer.class, claim.event.eventId(), claim.token, claim.token) == 1);
    }

    public boolean complete(Claim claim, boolean published) {
        return transaction(claim.event.roomId(), () -> {
            if (jdbc.queryForObject("""
                    SELECT COUNT(*) FROM chat_room_runtime WHERE environment=? AND room_id=?
                        AND dispatch_token=? AND dispatch_until>UTC_TIMESTAMP(6)
                    """, Integer.class, environment, claim.event.roomId(), claim.token) != 1) {
                return false;
            }
            long retryMicros = Math.min(5000000, Math.min(5000, 500L << Math.min(claim.attempts, 4)) * 1000
                    + ThreadLocalRandom.current().nextLong(100000));
            int changed = new NamedParameterJdbcTemplate(jdbc).update("""
                    UPDATE chat_message_outbox SET status=CASE WHEN expires_at<=UTC_TIMESTAMP(6) THEN 'EXPIRED'
                        WHEN :published THEN 'SENT' ELSE 'PENDING' END,claim_token=NULL,
                        attempts=LEAST(attempts+1,30),
                        next_attempt_at=TIMESTAMPADD(MICROSECOND,:retryMicros,UTC_TIMESTAMP(6))
                    WHERE environment=:environment AND room_id=:room AND event_id IN (:ids)
                        AND claim_token=:token AND status='PENDING'
                    """, Map.of("published", published, "retryMicros", retryMicros,
                    "environment", environment, "room", claim.event.roomId(), "ids", paddedIds(claim.eventIds),
                    "token", claim.token));
            jdbc.update("""
                    UPDATE chat_room_runtime SET dispatch_token=NULL,dispatch_until=NULL
                    WHERE environment=? AND room_id=? AND dispatch_token=?
                    """, environment, claim.event.roomId(), claim.token);
            return changed > 0;
        });
    }

    public void cleanup() {
        // Claim only bounded expired rows; never range-update live rows scanned by the expiry predicate.
        transactions.executeWithoutResult(status -> {
            var named = new NamedParameterJdbcTemplate(jdbc);
            List<String> expired = jdbc.queryForList("""
                    SELECT event_id FROM chat_message_outbox WHERE environment=? AND status='PENDING'
                        AND (epoch<? OR expires_at<=UTC_TIMESTAMP(6))
                    ORDER BY event_id LIMIT 1000 FOR UPDATE SKIP LOCKED
                    """, String.class, environment, participation.epoch());
            if (!expired.isEmpty()) {
                named.update("""
                        UPDATE chat_message_outbox SET status='EXPIRED',claim_token=NULL
                        WHERE environment=:environment AND event_id IN (:ids) AND status='PENDING'
                        """, Map.of("environment", environment, "ids", expired));
            }
            List<String> removable = jdbc.queryForList("""
                    SELECT event_id FROM chat_message_outbox WHERE environment=? AND status<>'PENDING'
                        AND expires_at<=UTC_TIMESTAMP(6)
                    ORDER BY event_id LIMIT 1000 FOR UPDATE SKIP LOCKED
                    """, String.class, environment);
            if (!removable.isEmpty()) {
                named.update("DELETE FROM chat_message_outbox WHERE environment=:environment AND event_id IN (:ids)",
                        Map.of("environment", environment, "ids", removable));
            }
        });
    }

    private List<String> paddedIds(List<String> ids) {
        // One prepared SQL shape for every batch size; NULL never matches an outbox primary key.
        var padded = new ArrayList<>(ids);
        while (padded.size() < 100) {
            padded.add(null);
        }
        return padded;
    }

    private <T> T transaction(long room, Supplier<T> operation) {
        return transaction(room, false, operation);
    }

    private <T> T transaction(long room, boolean skipBusy, Supplier<T> operation) {
        if (!participation.available()) {
            throw new ChatParticipationUnavailableException();
        }
        long started = System.nanoTime();
        return transactions.execute(status -> {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
            long epoch = participation.epoch();
            var current = jdbc.queryForMap("""
                    SELECT epoch,mode FROM chat_runtime_control WHERE environment=? FOR SHARE
                    """, environment);
            if (((Number) current.get("epoch")).longValue() != epoch || !"RUNNING".equals(current.get("mode"))) {
                throw new ChatParticipationUnavailableException();
            }
            jdbc.queryForList("""
                    SELECT task_id FROM chat_runtime_tasks WHERE environment=? AND task_id=? FOR SHARE
                    """, String.class, environment, participation.taskId());
            if (skipBusy) {
                var rooms = jdbc.queryForList("""
                        SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=?
                        FOR UPDATE SKIP LOCKED
                        """, Long.class, environment, room);
                if (rooms.isEmpty()) {
                    return null; // Busy room: retry the unchanged ordered head on a later dispatch tick.
                }
            } else {
                jdbc.queryForObject("""
                        SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=? FOR UPDATE
                        """, Long.class, environment, room);
            }
            T result = operation.get();
            if (System.nanoTime() - started >= java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                    || !participation.available() || jdbc.queryForObject("""
                            SELECT COUNT(*) FROM chat_runtime_tasks WHERE environment=? AND task_id=? AND epoch=?
                                AND state='ACTIVE' AND lease_until>UTC_TIMESTAMP(6)
                            """, Integer.class, environment, participation.taskId(), epoch) != 1) {
                throw new ChatParticipationUnavailableException();
            }
            return result;
        });
    }
}
