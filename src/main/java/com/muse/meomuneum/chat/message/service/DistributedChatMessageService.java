package com.muse.meomuneum.chat.message.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.message.policy.ChatMessageFingerprint;
import com.muse.meomuneum.chat.message.policy.ChatMessageRejection;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.runtime.ChatRuntimeMetrics;

/** All mutations join the participation transaction; Redis and broker calls stay outside it. */
public final class DistributedChatMessageService {
    private ChatRuntimeMetrics metrics;

    @Autowired(required = false)
    public void setMetrics(ChatRuntimeMetrics metrics) {
        this.metrics = metrics;
    }

    private final JdbcTemplate jdbc;
    private final ChatParticipationService participation;
    private final ChatMessageStorageService storage;
    private final ChatMessageFingerprint fingerprints;
    private final String environment;

    public DistributedChatMessageService(JdbcTemplate jdbc, ChatParticipationService participation,
            ChatMessageStorageService storage, ChatMessageFingerprint fingerprints, String environment) {
        this.jdbc = jdbc;
        this.participation = participation;
        this.storage = storage;
        this.fingerprints = fingerprints;
        this.environment = environment;
        // Never silently reset counters/receipts on a key rotation or process restart.
        var required = jdbc.queryForList("""
                SELECT key_id FROM chat_message_receipts WHERE environment=? AND expires_at>UTC_TIMESTAMP(6)
                UNION SELECT r.key_id FROM chat_send_repetitions r JOIN chat_room_members m ON m.id=r.membership_id
                    WHERE r.environment=? AND m.deleted_at IS NULL
                """, String.class, environment, environment);
        if (!fingerprints.keyIds().containsAll(required)) {
            throw new IllegalArgumentException("Retain fingerprint keys for unexpired receipts and active memberships");
        }
    }

    public record Saved(ChatMessageResponse message, boolean deduplicated) {
    }

    public Saved send(long user, long room, long member, String socket, String client, String text, String masked) {
        long started = System.nanoTime();
        String outcome = "unavailable";
        try {
            Saved saved = store(user, room, member, socket, client, text, masked);
            outcome = saved.deduplicated() ? "deduplicated" : "stored";
            return saved;
        } catch (ChatMessageRejection rejected) {
            outcome = "rejected";
            throw rejected;
        } finally {
            if (metrics != null) {
                metrics.stored(started, outcome);
            }
        }
    }

    private Saved store(long user, long room, long member, String socket, String client, String text, String masked) {
        return participation.messageTransaction(user, room, member, socket, operation -> {
            var receipts = jdbc.queryForList("""
                    SELECT * FROM chat_message_receipts WHERE environment=? AND user_id=? AND client_message_id=?
                    """, environment, user, client);
            if (!receipts.isEmpty()) {
                var receipt = receipts.getFirst();
                if (((Number) receipt.get("membership_id")).longValue() != member
                        || ((Number) receipt.get("room_id")).longValue() != room
                        || !fingerprints.matches((String) receipt.get("key_id"),
                                (String) receipt.get("fingerprint"), text)) {
                    throw new ChatMessageRejection("CLIENT_ID_CONFLICT");
                }
                var messages = jdbc.query("""
                        SELECT m.*,u.nickname FROM chat_room_messages m JOIN users u ON u.id=m.user_id
                        JOIN chat_message_receipts r ON r.message_id=m.id
                        WHERE r.environment=? AND r.user_id=? AND r.client_message_id=?
                            AND r.expires_at>UTC_TIMESTAMP(6) AND m.created_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))
                        """, (r, i) -> response(r), environment, user, client);
                if (messages.isEmpty()) {
                    throw new ChatMessageRejection("RETRY_UNAVAILABLE");
                }
                return new Saved(messages.getFirst(), true);
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_messages WHERE user_id=? AND client_message_id=?",
                    Integer.class, user, client) != 0) {
                throw new ChatMessageRejection("CLIENT_ID_CONFLICT");
            }
            Long remaining = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),
                        TIMESTAMPADD(SECOND,1,last_accepted_at))),0) FROM chat_send_policy
                    WHERE environment=? AND user_id=?
                    """, Long.class, environment, user);
            if (remaining > 0) {
                throw new ChatMessageRejection("RATE_LIMIT", Math.max(1, (remaining + 999) / 1000));
            }
            var retained = jdbc.queryForList("""
                    SELECT DISTINCT key_id FROM chat_send_repetitions WHERE environment=? AND membership_id=?
                    """, String.class, environment, member);
            if (!fingerprints.keyIds().containsAll(retained)) {
                throw new IllegalStateException("Retained Chat fingerprint key required");
            }
            int count = 0;
            for (String key : fingerprints.keyIds()) {
                count += jdbc.queryForObject("""
                        SELECT COALESCE(SUM(accepted_count),0) FROM chat_send_repetitions
                        WHERE environment=? AND membership_id=? AND key_id=? AND fingerprint=?
                        """, Integer.class, environment, member, key, fingerprints.digest(key, text));
            }
            if (count >= 2) {
                throw new ChatMessageRejection("DUPLICATE_CONTENT");
            }
            ChatMessageResponse message = operation.save(() -> ChatMessageResponse.from(
                    storage.store(user, room, client, masked).message()));
            String key = fingerprints.activeId();
            String fingerprint = fingerprints.digest(key, text);
            jdbc.update("""
                    INSERT INTO chat_send_policy(environment,user_id,last_accepted_at) VALUES (?,?,UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE last_accepted_at=UTC_TIMESTAMP(6)
                    """, environment, user);
            jdbc.update("""
                    INSERT INTO chat_send_repetitions(environment,membership_id,key_id,fingerprint,accepted_count)
                    VALUES (?,?,?,?,1) ON DUPLICATE KEY UPDATE accepted_count=accepted_count+1
                    """, environment, member, key, fingerprint);
            jdbc.update("""
                    INSERT INTO chat_message_receipts(environment,user_id,client_message_id,membership_id,room_id,
                        key_id,fingerprint,message_id,expires_at)
                    SELECT ?,?,?,?,?,?,?,id,TIMESTAMPADD(HOUR,24,created_at) FROM chat_room_messages WHERE id=?
                    """, environment, user, client, member, room, key, fingerprint, message.messageId());
            jdbc.update("""
                    INSERT INTO chat_message_outbox(event_id,environment,epoch,producer_id,room_id,room_seq,
                        message_id,occurred_at,expires_at,next_attempt_at)
                    SELECT ?,o.environment,o.epoch,?,o.room_id,o.room_seq,m.id,m.created_at,
                        TIMESTAMPADD(HOUR,24,m.created_at),UTC_TIMESTAMP(6)
                    FROM chat_message_order o JOIN chat_room_messages m ON m.id=o.message_id
                    WHERE o.environment=? AND o.message_id=?
                    """, UUID.randomUUID().toString(), "chat:" + participation.taskId(), environment,
                    message.messageId());
            return new Saved(message, false);
        });
    }

    public void cleanup() {
        jdbc.update("DELETE FROM chat_message_receipts WHERE environment=? AND expires_at<=UTC_TIMESTAMP(6) LIMIT 1000",
                environment);
        jdbc.update("""
                DELETE FROM chat_send_repetitions WHERE environment=? AND membership_id IN
                    (SELECT id FROM chat_room_members WHERE deleted_at IS NOT NULL) LIMIT 1000
                """, environment);
    }

    private ChatMessageResponse response(ResultSet r) throws SQLException {
        return new ChatMessageResponse(r.getLong("id"), r.getString("client_message_id"), r.getLong("room_id"),
                r.getLong("user_id"), r.getString("nickname"), r.getString("content"),
                r.getObject("created_at", LocalDateTime.class).atOffset(ZoneOffset.UTC));
    }
}
