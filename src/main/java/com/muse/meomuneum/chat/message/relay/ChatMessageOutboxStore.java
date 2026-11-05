package com.muse.meomuneum.chat.message.relay;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
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

    public record Claim(ChatRedisEvent event, String token, int attempts) {
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
        return transaction(room, () -> {
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
                    ORDER BY room_seq,event_id LIMIT 1 FOR UPDATE
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
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    UPDATE chat_room_runtime SET dispatch_token=?,dispatch_until=TIMESTAMPADD(SECOND,5,UTC_TIMESTAMP(6))
                    WHERE environment=? AND room_id=?
                    """, token, environment, room);
            jdbc.update("UPDATE chat_message_outbox SET claim_token=? WHERE event_id=?", token, id);
            Object at = row.get("occurred_at");
            LocalDateTime time = at instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) at;
            ChatRedisEvent event = "CHAT_MESSAGE_CREATED".equals(row.get("event_type"))
                    ? new ChatMessageRedisEvent(id, (String) row.get("producer_id"),
                            ((Number) row.get("epoch")).longValue(), room, ((Number) row.get("room_seq")).longValue(),
                            ((Number) row.get("message_id")).longValue(), time.toInstant(ZoneOffset.UTC))
                    : ChatControlEvent.decode((String) row.get("event_body"));
            return new Claim(event, token, ((Number) row.get("attempts")).intValue());
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
            int attempts = Math.min(claim.attempts + 1, 30);
            long retryMicros = Math.min(5000000, Math.min(5000, 500L << Math.min(claim.attempts, 4)) * 1000
                    + ThreadLocalRandom.current().nextLong(100000));
            int changed = jdbc.update("""
                    UPDATE chat_message_outbox SET status=CASE WHEN expires_at<=UTC_TIMESTAMP(6) THEN 'EXPIRED'
                        WHEN ? THEN 'SENT' ELSE 'PENDING' END,claim_token=NULL,attempts=?,
                        next_attempt_at=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6))
                    WHERE event_id=? AND claim_token=? AND status='PENDING'
                    """, published, attempts, retryMicros, claim.event.eventId(), claim.token);
            jdbc.update("""
                    UPDATE chat_room_runtime SET dispatch_token=NULL,dispatch_until=NULL
                    WHERE environment=? AND room_id=? AND dispatch_token=?
                    """, environment, claim.event.roomId(), claim.token);
            return changed == 1;
        });
    }

    public void cleanup() {
        // Old-epoch live publication must never resume. Stored messages remain governed by their own retention.
        jdbc.update("""
                UPDATE chat_message_outbox SET status='EXPIRED',claim_token=NULL WHERE environment=?
                    AND status='PENDING' AND (epoch<? OR expires_at<=UTC_TIMESTAMP(6)) LIMIT 1000
                """, environment, participation.epoch());
        jdbc.update("""
                DELETE FROM chat_message_outbox WHERE environment=? AND status<>'PENDING'
                    AND expires_at<=UTC_TIMESTAMP(6) LIMIT 1000
                """, environment);
    }

    private <T> T transaction(long room, Supplier<T> operation) {
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
            jdbc.queryForObject("""
                    SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=? FOR UPDATE
                    """, Long.class, environment, room);
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
