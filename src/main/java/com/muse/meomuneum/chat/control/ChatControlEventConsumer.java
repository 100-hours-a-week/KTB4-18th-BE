package com.muse.meomuneum.chat.control;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

/** Pub/Sub accelerates terminal cleanup; DB polling remains independent of notification delivery. */
public final class ChatControlEventConsumer {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ChatParticipationService participation;
    private final ChatViewLifecycle views;
    private final ChatSocketSessions sockets;
    private final String environment;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>(128, 0.75f, true);
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();

    public ChatControlEventConsumer(JdbcTemplate jdbc, ChatParticipationService participation, ChatViewLifecycle views,
            ChatSocketSessions sockets, String environment) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.participation = participation;
        this.views = views;
        this.sockets = sockets;
        this.environment = environment;
        participation.enableControlEvents();
    }

    public void rejectMalformed() {
        rejected.incrementAndGet();
    }

    public long rejectedEvents() {
        return rejected.get();
    }

    public long appliedEvents() {
        return applied.get();
    }

    public void receive(String channel, ChatRedisChannels channels, String body) {
        ChatControlEvent event;
        try {
            event = ChatControlEvent.decode(body);
            if (!event.channel(channels).equals(channel) || event.epoch() != participation.epoch()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            rejected.incrementAndGet();
            return;
        }
        if (duplicate(event.eventId(), false)) {
            return;
        }
        participation.validSockets();
        Integer committed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_message_outbox x JOIN chat_runtime_tasks t
                    ON x.producer_id=CONCAT('chat:',t.task_id) AND t.environment=x.environment AND t.epoch=x.epoch
                WHERE x.event_id=? AND x.environment=? AND x.epoch=? AND x.producer_id=? AND x.event_type=?
                    AND x.room_id=? AND x.room_seq=? AND BINARY x.event_body=BINARY ?
                    AND x.expires_at>UTC_TIMESTAMP(6)
                """, Integer.class, event.eventId(), environment, event.epoch(), event.producerId(), event.type(),
                event.roomId(), event.roomSeq(), event.encode());
        if (committed != 1) {
            rejected.incrementAndGet();
            return;
        }
        if (event.type().equals("CHAT_PRESENCE_CHANGED")) {
            // A delayed lower sequence is only a hint: always publish the latest DB snapshot/version.
            participation.publishPresence(event.roomId());
        } else if (!terminal(event)) {
            rejected.incrementAndGet();
            return;
        }
        duplicate(event.eventId(), true);
        applied.incrementAndGet();
    }

    private boolean terminal(ChatControlEvent event) {
        var payload = event.payload();
        long user = (long) payload.get("user_id");
        long member = (long) payload.get("membership_id");
        String wireReason = (String) payload.get("reason");
        String reason = wireReason.equals("LAST_VIEW_LEFT") ? "LEFT" : wireReason;
        String view = (String) payload.get("view_id");
        Integer confirmed = view == null
                ? jdbc.queryForObject("""
                        SELECT COUNT(*) FROM chat_room_members WHERE id=? AND user_id=? AND room_id=?
                            AND auth_session_id=? AND deleted_at IS NOT NULL AND COALESCE(end_reason,'LEFT')=?
                        """, Integer.class, member, user, event.roomId(), payload.get("sid"), reason)
                : jdbc.queryForObject("""
                        SELECT COUNT(*) FROM chat_view_runtime v JOIN chat_view_sessions s ON s.id=v.view_id
                        JOIN chat_room_members m ON m.id=v.membership_id
                        WHERE v.environment=? AND v.epoch=? AND v.view_id=? AND v.user_id=? AND v.room_id=?
                            AND v.membership_id=? AND v.state='LEFT' AND s.ended_at IS NOT NULL
                            AND m.auth_session_id=? AND v.end_reason=? AND v.changed_seq>=?
                        """, Integer.class, environment, event.epoch(), view, user, event.roomId(), member,
                        payload.get("sid"), reason, event.roomSeq());
        if (confirmed != 1) {
            return false;
        }
        close(views.discardEnded(user, event.roomId(), member, view), reason, user);
        return true;
    }

    public void reconcile() {
        Set<String> valid = participation.validSockets();
        var local = views.localViews();
        var invalid = new ArrayList<>(
                local.entrySet().stream().filter(entry -> !valid.contains(entry.getKey())).toList());
        for (int offset = 0; offset < invalid.size(); offset += 100) {
            var batch = invalid.subList(offset, Math.min(offset + 100, invalid.size()));
            var ids = batch.stream().map(entry -> entry.getValue().viewId()).toList();
            Map<String, Map<String, Object>> rows = new java.util.HashMap<>();
            named.queryForList("""
                    SELECT v.view_id,v.user_id,v.room_id,v.membership_id,v.end_reason,m.end_reason AS member_reason
                    FROM chat_view_runtime v JOIN chat_room_members m ON m.id=v.membership_id
                    WHERE v.environment=:environment AND v.view_id IN (:views)
                    """, new MapSqlParameterSource("environment", environment).addValue("views", ids))
                    .forEach(row -> rows.put((String) row.get("view_id"), row));
            for (var entry : batch) {
                var context = entry.getValue();
                var row = rows.get(context.viewId());
                String reason = "LEFT";
                if (row != null && ((Number) row.get("user_id")).longValue() == context.userId()
                        && ((Number) row.get("room_id")).longValue() == context.roomId()
                        && ((Number) row.get("membership_id")).longValue() == context.membershipId()) {
                    Object ended = row.get("end_reason") == null ? row.get("member_reason") : row.get("end_reason");
                    if (ended != null && ChatControlEvent.REASONS.contains(ended.toString())) {
                        reason = ended.toString();
                    }
                }
                close(views.discardInvalidSocket(entry.getKey(), context),
                        reason, context.userId());
            }
        }
    }

    private void close(Set<String> connections, String reason, long user) {
        if (reason.equals("ACCESS_RESTRICTED")) {
            LocalDateTime until = jdbc.queryForObject("""
                    SELECT MAX(expires_at) FROM chat_bans WHERE user_id=? AND deleted_at IS NULL
                        AND created_at<=UTC_TIMESTAMP(6) AND expires_at>UTC_TIMESTAMP(6)
                    """, LocalDateTime.class, user);
            sockets.close(connections, 4101, "CHAT_BANNED|" + (until == null ? "" : until.atOffset(ZoneOffset.UTC)));
        } else {
            sockets.close(connections, 4100, reason.equals("SESSION_REVOKED") ? "CHAT_LOGGED_OUT" : "CHAT_LEFT");
        }
    }

    private synchronized boolean duplicate(String id, boolean record) {
        long now = System.nanoTime();
        seen.values().removeIf(deadline -> deadline - now <= 0);
        if (seen.get(id) != null) {
            return true;
        }
        if (record) {
            seen.put(id, now + TimeUnit.MINUTES.toNanos(10));
            while (seen.size() > 10000) {
                seen.remove(seen.keySet().iterator().next());
            }
        }
        return false;
    }
}
