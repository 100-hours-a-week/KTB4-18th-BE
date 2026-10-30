package com.muse.meomuneum.chat.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;

/** Offline operator workflow. No application automatically calls begin/finish or reopens a failed Task. */
public final class ChatRuntimeRecovery {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String environment;

    public ChatRuntimeRecovery(JdbcTemplate jdbc, PlatformTransactionManager manager, String environment) {
        if (environment == null || !environment.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Chat recovery environment required");
        }
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(1);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(2);
        this.environment = environment;
    }

    public record Lease(long epoch, String token, String generation, String serverId) {
        public String marker() {
            return epoch + ":" + generation + ":" + serverId;
        }
    }

    public Lease begin(String serverId) {
        if (serverId == null || !serverId.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Redis server generation required");
        }
        return transaction(() -> {
            jdbc.update("INSERT IGNORE INTO chat_runtime_control(environment) VALUES (?)", environment);
            var row = lock();
            if ("RECOVERING".equals(row.get("mode")) && liveLease()) {
                throw new IllegalStateException("Chat recovery already has a live owner");
            }
            long epoch = ((Number) row.get("epoch")).longValue();
            if ("RUNNING".equals(row.get("mode"))) {
                epoch = Math.addExact(epoch, 1);
            }
            if (epoch > 9007199254740991L) {
                throw new IllegalStateException("Chat epoch exhausted");
            }
            var lease = new Lease(epoch, UUID.randomUUID().toString(), UUID.randomUUID().toString(), serverId);
            jdbc.update(
                    """
                            UPDATE chat_runtime_control SET mode='RECOVERING',epoch=?,recovery_token=?,
                                recovery_lease_until=TIMESTAMPADD(SECOND,15,UTC_TIMESTAMP(6)),
                                redis_generation=?,redis_server_id=?
                            WHERE environment=?
                            """,
                    epoch, lease.token(), lease.generation(), serverId, environment);
            return lease;
        });
    }

    /** Control UPDATE lock fences normal SHARE-locked writes; each bounded chunk is independently resumable. */
    public boolean chunk(Lease lease) {
        return transaction(() -> {
            require(lease);
            var tasks = jdbc.queryForList("""
                    SELECT task_id FROM chat_runtime_tasks WHERE environment=? AND state<>'FENCED'
                    ORDER BY task_id LIMIT 100
                    """, String.class, environment);
            for (String task : tasks) {
                jdbc.update("UPDATE chat_runtime_tasks SET state='FENCED' WHERE environment=? AND task_id=?",
                        environment, task);
            }
            var views = jdbc.queryForList("""
                    SELECT view_id,room_id FROM chat_view_runtime WHERE environment=? AND epoch<>? AND state<>'LEFT'
                    ORDER BY view_id LIMIT 100
                    """, environment, lease.epoch());
            for (var view : views) {
                jdbc.update("""
                        UPDATE chat_view_intervals SET ends_at=verified_at,end_message_id=verified_through_message_id,
                            end_room_seq=verified_through_room_seq,end_reason='RESTART'
                        WHERE view_session_id=? AND ends_at IS NULL
                        """, view.get("view_id"));
                jdbc.update("""
                        UPDATE chat_view_runtime SET epoch=?,state='OFFLINE',deadline=NULL,owner_task_id=NULL,
                            socket_token=NULL,owner_generation=owner_generation+1 WHERE view_id=? AND environment=?
                        """, lease.epoch(), view.get("view_id"), environment);
                jdbc.update("""
                        UPDATE chat_room_runtime SET room_seq=room_seq+1,dispatch_token=NULL,dispatch_until=NULL
                        WHERE environment=? AND room_id=?
                        """, environment, view.get("room_id"));
            }
            int expired = jdbc.update("""
                    UPDATE chat_message_outbox SET status='EXPIRED',claim_token=NULL
                    WHERE environment=? AND epoch<>? AND status='PENDING' LIMIT 1000
                    """, environment, lease.epoch());
            if (jdbc.update("""
                    UPDATE chat_runtime_control SET recovery_lease_until=TIMESTAMPADD(SECOND,15,UTC_TIMESTAMP(6))
                    WHERE environment=? AND recovery_token=? AND recovery_lease_until>UTC_TIMESTAMP(6)
                    """, environment, lease.token()) != 1) {
                throw new IllegalStateException("Chat recovery lease lost");
            }
            return !tasks.isEmpty() || !views.isEmpty() || expired > 0;
        });
    }

    public void finish(Lease lease, ChatRedisConnection redis, ChatRedisChannels channels) {
        // Network calls occur outside the DB lock. A stale writer cannot open DB authority; a mismatched
        // marker only keeps Tasks closed. A takeover changes the generation as well as the DB token.
        transaction(() -> {
            require(lease);
            return null;
        });
        if (!serverId(redis).equals(lease.serverId()) || !redis.areSubscriptionsReady()) {
            throw new IllegalStateException("Redis recovery readiness changed");
        }
        redis.commands().sync().hset(channels.runtimeMarkerKey(), "generation", lease.marker());
        if (!lease.marker().equals(redis.commands().sync().hget(channels.runtimeMarkerKey(), "generation"))) {
            throw new IllegalStateException("Redis recovery marker changed");
        }
        transaction(() -> {
            require(lease);
            int unfinished = jdbc.queryForObject("""
                    SELECT (SELECT COUNT(*) FROM chat_runtime_tasks WHERE environment=? AND state<>'FENCED')
                      +(SELECT COUNT(*) FROM chat_view_runtime WHERE environment=? AND epoch<>? AND state<>'LEFT')
                      +(SELECT COUNT(*) FROM chat_message_outbox WHERE environment=? AND epoch<>? AND status='PENDING')
                    """, Integer.class, environment, environment, lease.epoch(), environment, lease.epoch());
            if (unfinished != 0) {
                throw new IllegalStateException("Chat recovery chunks are incomplete");
            }
            int completed = jdbc.update("""
                    UPDATE chat_runtime_control SET mode='RUNNING',recovery_token=NULL,recovery_lease_until=NULL
                    WHERE environment=? AND recovery_token=? AND recovery_lease_until>UTC_TIMESTAMP(6)
                    """, environment, lease.token());
            if (completed != 1) {
                throw new IllegalStateException("Chat recovery lease lost");
            }
            return null;
        });
    }

    public static String serverId(ChatRedisConnection redis) {
        for (String line : redis.commands().sync().info("server").split("\\r?\\n")) {
            if (line.matches("run_id:[0-9a-f]{40}")) {
                return line.substring(7);
            }
        }
        throw new IllegalStateException("Redis server generation unavailable");
    }

    private Map<String, Object> lock() {
        return jdbc.queryForMap("SELECT * FROM chat_runtime_control WHERE environment=? FOR UPDATE", environment);
    }

    private boolean liveLease() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_runtime_control WHERE environment=?
                    AND recovery_lease_until>UTC_TIMESTAMP(6)
                """, Integer.class, environment) == 1;
    }

    private void require(Lease lease) {
        var row = lock();
        if (!"RECOVERING".equals(row.get("mode")) || !lease.token().equals(row.get("recovery_token"))
                || !lease.generation().equals(row.get("redis_generation"))
                || ((Number) row.get("epoch")).longValue() != lease.epoch() || !liveLease()) {
            throw new IllegalStateException("Chat recovery lease lost");
        }
    }

    private <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
            return work.get();
        });
    }
}
