package com.muse.meomuneum.chat.music;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;

/** Short fenced transactions only; PUBLISH is performed after claim and publishable return. */
public final class ChatMusicOutboxStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ChatParticipationService participation;
    private final String environment;
    private long afterRoom;

    public ChatMusicOutboxStore(JdbcTemplate source, PlatformTransactionManager manager,
            ChatParticipationService participation, String environment) {
        jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(1);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(2);
        this.participation = participation;
        this.environment = environment;
    }

    public record Claim(ChatMusicRedisEvent event, String token, int attempts) {
    }

    public List<Long> candidateRooms() {
        var rooms = jdbc.queryForList("""
                SELECT DISTINCT room_id FROM chat_music_outbox WHERE environment=? AND epoch=? AND room_id>?
                  AND status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(6)
                  AND (claim_until IS NULL OR claim_until<=UTC_TIMESTAMP(6)) ORDER BY room_id LIMIT 25
                """, Long.class, environment, participation.epoch(), afterRoom);
        afterRoom = rooms.isEmpty() ? 0 : rooms.getLast();
        return rooms;
    }

    public Claim claim(long room) {
        if (participation.transitionBusy()) {
            return null;
        }
        return transaction(room, true, () -> {
            var rows = jdbc.query("""
                    SELECT *,next_attempt_at<=UTC_TIMESTAMP(6) due,expires_at>UTC_TIMESTAMP(6) unexpired,
                      (claim_until IS NULL OR claim_until<=UTC_TIMESTAMP(6)) unclaimed
                    FROM chat_music_outbox WHERE environment=? AND epoch=? AND room_id=? AND status='PENDING'
                    ORDER BY music_version,event_type,event_id LIMIT 1 FOR UPDATE
                    """, (r, i) -> new Pending(new ChatMusicRedisEvent(r.getString("event_id"), environment,
                    r.getString("producer_id"), r.getLong("epoch"), r.getLong("room_id"), r.getLong("room_seq"),
                    r.getLong("music_version"), r.getString("event_type"),
                    r.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                    r.getInt("attempts"), r.getBoolean("due"), r.getBoolean("unexpired"), r.getBoolean("unclaimed")),
                    environment, participation.epoch(), room);
            if (rows.isEmpty()) {
                return null;
            }
            Pending head = rows.getFirst();
            if (!head.unexpired()) {
                jdbc.update("UPDATE chat_music_outbox SET status='EXPIRED',claim_token=NULL,claim_until=NULL "
                        + "WHERE event_id=?", head.event().eventId());
                return null;
            }
            if (!head.due() || !head.unclaimed()) {
                return null;
            }
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    UPDATE chat_music_outbox SET claim_token=?,claim_until=TIMESTAMPADD(SECOND,5,UTC_TIMESTAMP(6))
                    WHERE event_id=?
                    """, token, head.event().eventId());
            return new Claim(head.event(), token, head.attempts());
        });
    }

    public boolean publishable(Claim claim) {
        return transaction(claim.event().roomId(), false, () -> owned(claim));
    }

    private boolean owned(Claim claim) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_music_outbox WHERE environment=? AND epoch=? AND event_id=?
                  AND claim_token=? AND claim_until>UTC_TIMESTAMP(6) AND status='PENDING'
                  AND expires_at>UTC_TIMESTAMP(6)
                """, Integer.class, environment, participation.epoch(), claim.event().eventId(), claim.token()) == 1;
    }

    public boolean complete(Claim claim, boolean published) {
        return transaction(claim.event().roomId(), false, () -> {
            if (!owned(claim)) {
                return false;
            }
            long delay = Math.min(5_000_000L, (500_000L << Math.min(claim.attempts(), 4))
                    + ThreadLocalRandom.current().nextLong(100_000));
            return jdbc.update("""
                    UPDATE chat_music_outbox SET status=CASE WHEN ? THEN 'SENT' ELSE 'PENDING' END,
                      sent_at=CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE NULL END,claim_token=NULL,claim_until=NULL,
                      attempts=LEAST(attempts+1,30),next_attempt_at=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6))
                    WHERE event_id=? AND claim_token=? AND status='PENDING'
                    """, published, published, delay, claim.event().eventId(), claim.token()) == 1;
        });
    }

    public boolean confirms(ChatMusicRedisEvent event) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_music_outbox x JOIN chat_runtime_tasks t
                  ON x.producer_id=CONCAT('chat:',t.task_id) AND t.environment=x.environment AND t.epoch=x.epoch
                WHERE x.event_id=? AND x.environment=? AND x.epoch=? AND x.producer_id=? AND x.room_id=?
                  AND x.room_seq=? AND x.music_version=? AND x.event_type=? AND x.occurred_at=?
                  AND x.status<>'EXPIRED' AND x.expires_at>UTC_TIMESTAMP(6)
                """, Integer.class, event.eventId(), environment, event.epoch(), event.producerId(), event.roomId(),
                event.roomSeq(), event.musicVersion(), event.type(),
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)) == 1;
    }

    public void cleanup() {
        // Receipt tombstones and queue history are intentionally outside this cleanup.
        transactions.executeWithoutResult(status -> {
            controlAndTask();
            var expired = jdbc.queryForList("""
                    SELECT event_id FROM chat_music_outbox WHERE environment=? AND status='PENDING'
                      AND (epoch<? OR expires_at<=UTC_TIMESTAMP(6)) ORDER BY event_id LIMIT 500
                    FOR UPDATE SKIP LOCKED
                    """, String.class, environment, participation.epoch());
            var named = new NamedParameterJdbcTemplate(jdbc);
            if (!expired.isEmpty()) {
                named.update("""
                        UPDATE chat_music_outbox SET status='EXPIRED',claim_token=NULL,claim_until=NULL
                        WHERE environment=:env AND event_id IN (:ids)
                        """, java.util.Map.of("env", environment, "ids", expired));
            }
            var removable = jdbc.queryForList("""
                    SELECT event_id FROM chat_music_outbox WHERE environment=? AND status<>'PENDING'
                      AND expires_at<=UTC_TIMESTAMP(6) ORDER BY event_id LIMIT 500 FOR UPDATE SKIP LOCKED
                    """, String.class, environment);
            if (!removable.isEmpty()) {
                named.update("DELETE FROM chat_music_outbox WHERE environment=:env AND event_id IN (:ids)",
                        java.util.Map.of("env", environment, "ids", removable));
            }
            requireTask();
        });
    }

    private <T> T transaction(long room, boolean skipBusy, Supplier<T> action) {
        if (!participation.available()) {
            throw new ChatParticipationUnavailableException();
        }
        long started = System.nanoTime();
        return transactions.execute(status -> {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
            controlAndTask();
            var rooms = jdbc.queryForList("SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=? "
                    + (skipBusy ? "FOR UPDATE SKIP LOCKED" : "FOR UPDATE"), Long.class, environment, room);
            if (rooms.isEmpty()) {
                return null;
            }
            T result = action.get();
            if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(2)) {
                throw new ChatParticipationUnavailableException();
            }
            requireTask();
            return result;
        });
    }

    private void controlAndTask() {
        var current = jdbc.queryForMap("SELECT epoch,mode FROM chat_runtime_control WHERE environment=? FOR SHARE",
                environment);
        if (((Number) current.get("epoch")).longValue() != participation.epoch()
                || !"RUNNING".equals(current.get("mode"))) {
            throw new ChatParticipationUnavailableException();
        }
        jdbc.queryForList("""
                SELECT task_id FROM chat_runtime_tasks WHERE environment=? AND task_id=? FOR SHARE
                """, String.class, environment, participation.taskId());
        requireTask();
    }

    private void requireTask() {
        if (!participation.available() || jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_runtime_tasks WHERE environment=? AND task_id=? AND epoch=?
                  AND state='ACTIVE' AND lease_until>UTC_TIMESTAMP(6)
                """, Integer.class, environment, participation.taskId(), participation.epoch()) != 1) {
            throw new ChatParticipationUnavailableException();
        }
    }

    private record Pending(ChatMusicRedisEvent event, int attempts, boolean due, boolean unexpired, boolean unclaimed) {
    }
}
