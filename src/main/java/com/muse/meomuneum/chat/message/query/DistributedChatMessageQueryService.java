package com.muse.meomuneum.chat.message.query;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.participation.ChatParticipationService;

/** Fixed DB range snapshots; a remote REST reader never checkpoints the socket owner's ranges. */
@Service
@ConditionalOnProperty(name = "chat.distributed-presence.enabled", havingValue = "true")
public class DistributedChatMessageQueryService {
    private final ChatParticipationService participation;
    private final NamedParameterJdbcTemplate jdbc;
    private final ChatCursorCodec cursors;

    public DistributedChatMessageQueryService(ChatParticipationService participation,
            NamedParameterJdbcTemplate jdbc, ChatCursorCodec cursors) {
        this.participation = participation;
        this.jdbc = jdbc;
        this.cursors = cursors;
    }

    public Map<String, Object> ready(long user, long room, long member, String view, long last) {
        return participation.read(user, room, member, view, context -> {
            ChatQueryCursor cursor = create(user, room, member, view, "recovery", context);
            cursor = cursor.next(Math.min(last, cursor.snapshot()));
            return Map.of("room_id", room, "membership_id", member, "view_session_id", view,
                    "recovery_cursor", cursors.encode(cursor), "snapshot_message_id", cursor.snapshot());
        });
    }

    public ChatMessagePage query(long user, long room, long member, String view, String mode, String token, int size) {
        return participation.read(user, room, member, view, context -> {
            ChatQueryCursor cursor = token == null
                    ? create(user, room, member, view, mode, context)
                    : cursors.decode(token);
            verify(cursor, user, room, member, view, mode, context);
            var params = params(cursor).addValue("limit", size + 1).addValue("position", cursor.position());
            String position = cursor.position() == 0
                    ? ""
                    : " AND m.id " + (mode.equals("history") ? "<" : ">")
                            + " :position";
            List<ChatMessageResponse> loaded = jdbc.query(select() + position + " ORDER BY m.id "
                    + (mode.equals("history") ? "DESC" : "ASC") + " LIMIT :limit", params, (r, i) -> response(r));
            boolean next = loaded.size() > size;
            List<ChatMessageResponse> items = List.copyOf(loaded.subList(0, Math.min(size, loaded.size())));
            return new ChatMessagePage(items, next ? cursors.encode(cursor.next(items.getLast().messageId())) : null,
                    next, cursor.snapshot(), gaps(cursor));
        });
    }

    public ChatMessageResponse byClientId(long user, long room, long member, String view, String client) {
        try {
            if (!UUID.fromString(client).toString().equalsIgnoreCase(client)) {
                throw new ChatQueryException(false);
            }
        } catch (RuntimeException invalid) {
            throw new ChatQueryException(false);
        }
        return participation.read(user, room, member, view, context -> {
            ChatQueryCursor cursor = create(user, room, member, view, "history", context);
            List<ChatMessageResponse> found = jdbc.query(
                    select() + " AND m.user_id=:user AND m.client_message_id=:client",
                    params(cursor).addValue("user", user).addValue("client", client.toLowerCase(java.util.Locale.ROOT)),
                    (r, i) -> response(r));
            if (found.isEmpty()) {
                throw new ChatQueryException(true);
            }
            return found.getFirst();
        });
    }

    private ChatQueryCursor create(long user, long room, long member, String view, String mode,
            ChatParticipationService.ReadContext context) {
        String id = UUID.randomUUID().toString();
        var params = new MapSqlParameterSource("id", id).addValue("environment", context.environment())
                .addValue("epoch", context.epoch()).addValue("generation", context.generation()).addValue("user", user)
                .addValue("room", room).addValue("member", member).addValue("view", view).addValue("mode", mode)
                .addValue("sequence", context.roomSeq()).addValue("asof", context.asOf())
                .addValue("expires", context.asOf().plusHours(24));
        jdbc.update("""
                INSERT INTO chat_query_snapshots(id,environment,epoch,owner_generation,user_id,room_id,membership_id,
                    view_id,mode,snapshot_message_id,room_seq,as_of,expires_at)
                VALUES (:id,:environment,:epoch,:generation,:user,:room,:member,:view,:mode,0,:sequence,:asof,:expires)
                """, params);
        jdbc.update("""
                INSERT INTO chat_query_snapshot_ranges(snapshot_id,interval_id,start_message_id,end_message_id,
                    start_room_seq,end_room_seq,started_at,ends_at)
                SELECT :id,i.id,i.start_after_message_id,
                    CASE WHEN i.ends_at IS NULL THEN i.verified_through_message_id ELSE i.end_message_id END,
                    i.start_after_room_seq,
                    CASE WHEN i.ends_at IS NULL THEN i.verified_through_room_seq ELSE i.end_room_seq END,
                    i.started_at,LEAST(COALESCE(i.ends_at,i.verified_at),
                        COALESCE(v.ended_at,COALESCE(i.ends_at,i.verified_at)),
                        COALESCE(m.deleted_at,COALESCE(i.ends_at,i.verified_at)))
                FROM chat_view_sessions v JOIN chat_room_members m ON m.id=v.membership_id
                JOIN chat_view_intervals i ON i.view_session_id=v.id
                WHERE v.user_id=:user AND v.room_id=:room AND i.environment=:environment
                    AND (:mode='history' OR v.id=:view) AND i.start_after_room_seq IS NOT NULL
                    AND i.started_at<=:asof AND COALESCE(i.ends_at,i.verified_at)>TIMESTAMPADD(HOUR,-24,:asof)
                """, params);
        Long max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(m.id),0) FROM chat_room_messages m JOIN chat_message_order o ON o.message_id=m.id
                JOIN chat_query_snapshots s ON s.id=:id AND s.environment=o.environment AND s.room_id=m.room_id
                WHERE m.created_at<=s.as_of AND m.created_at>TIMESTAMPADD(HOUR,-24,s.as_of)
                    AND o.room_seq<=s.room_seq AND
                """ + permitted(), params, Long.class);
        jdbc.update("UPDATE chat_query_snapshots SET snapshot_message_id=:max WHERE id=:id",
                params.addValue("max", max));
        return new ChatQueryCursor(user, room, member, view, mode, max, 0,
                context.asOf().plusHours(24).toInstant(ZoneOffset.UTC).toEpochMilli(),
                mode.equals("history") ? context.asOf().toString() : null, id, context.epoch(), context.generation());
    }

    private void verify(ChatQueryCursor cursor, long user, long room, long member, String view, String mode,
            ChatParticipationService.ReadContext context) {
        if (cursor.userId() != user || cursor.roomId() != room || cursor.membershipId() != member
                || !view.equals(cursor.viewId()) || !mode.equals(cursor.mode()) || cursor.epoch() != context.epoch()
                || cursor.generation() != context.generation() || cursor.snapshotId() == null) {
            throw new ChatQueryException(false);
        }
        Integer valid = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_query_snapshots WHERE id=:id AND environment=:environment
                    AND epoch=:epoch AND owner_generation=:generation AND user_id=:user AND room_id=:room
                    AND membership_id=:member AND view_id=:view AND mode=:mode AND snapshot_message_id=:max
                    AND expires_at>UTC_TIMESTAMP(6)
                """, params(cursor).addValue("environment", context.environment()).addValue("epoch", context.epoch())
                .addValue("generation", context.generation()).addValue("user", user).addValue("room", room)
                .addValue("member", member).addValue("view", view).addValue("mode", mode)
                .addValue("max", cursor.snapshot()),
                Integer.class);
        if (valid == null || valid != 1) {
            throw new ChatQueryException(false);
        }
    }

    private String select() {
        return """
                SELECT m.*,u.nickname FROM chat_room_messages m JOIN users u ON u.id=m.user_id
                JOIN chat_message_order o ON o.message_id=m.id
                JOIN chat_query_snapshots s ON s.id=:id AND s.environment=o.environment AND s.room_id=m.room_id
                WHERE m.created_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6)) AND m.created_at<=s.as_of
                    AND m.id<=s.snapshot_message_id AND o.room_seq<=s.room_seq AND
                """ + permitted();
    }

    private String permitted() {
        return """
                EXISTS(SELECT 1 FROM chat_query_snapshot_ranges r WHERE r.snapshot_id=s.id
                    AND m.id>r.start_message_id AND o.room_seq>r.start_room_seq AND m.created_at>=r.started_at
                    AND ((r.end_message_id IS NULL AND m.created_at<r.ends_at)
                         OR (r.end_message_id IS NOT NULL AND m.id<=r.end_message_id))
                    AND (r.end_room_seq IS NULL OR o.room_seq<=r.end_room_seq))
                """;
    }

    private List<ChatMessagePage.Gap> gaps(ChatQueryCursor cursor) {
        var rows = jdbc.queryForList("""
                SELECT start_message_id,end_message_id,started_at,ends_at FROM chat_query_snapshot_ranges
                WHERE snapshot_id=:id ORDER BY started_at,start_message_id
                """, params(cursor));
        var result = new ArrayList<ChatMessagePage.Gap>();
        LocalDateTime cutoff = jdbc.queryForObject("SELECT TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))",
                new MapSqlParameterSource(), LocalDateTime.class);
        LocalDateTime first = jdbc.queryForObject("""
                SELECT MIN(created_at) FROM chat_view_sessions WHERE user_id=:user AND room_id=:room
                    AND (:mode='history' OR id=:view)
                """, new MapSqlParameterSource("user", cursor.userId()).addValue("room", cursor.roomId())
                .addValue("view", cursor.viewId()).addValue("mode", cursor.mode()), LocalDateTime.class);
        if (first != null && !first.isAfter(cutoff)) {
            result.add(new ChatMessagePage.Gap("RETENTION", 0, cutoff.atOffset(ZoneOffset.UTC)));
        }
        LocalDateTime covered = null;
        Long coveredId = null;
        for (Map<String, Object> row : rows) {
            LocalDateTime start = date(row.get("started_at"));
            LocalDateTime end = date(row.get("ends_at"));
            if (!end.isAfter(cutoff)) {
                continue;
            }
            long startId = ((Number) row.get("start_message_id")).longValue();
            if (covered != null && (covered.isBefore(start)
                    || covered.equals(start) && coveredId != null && startId > coveredId)) {
                result.add(new ChatMessagePage.Gap("ABSENCE", startId, start.atOffset(ZoneOffset.UTC)));
            }
            if (covered == null || end.isAfter(covered)) {
                covered = end;
                coveredId = (Long) row.get("end_message_id");
            } else if (end.equals(covered) && coveredId != null) {
                coveredId = row.get("end_message_id") == null
                        ? null
                        : Math.max(coveredId, ((Number) row.get("end_message_id")).longValue());
            }
        }
        return List.copyOf(result);
    }

    private LocalDateTime date(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void purgeExpiredSnapshots() {
        try {
            jdbc.update("DELETE FROM chat_query_snapshots WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT 1000",
                    new MapSqlParameterSource());
        } catch (RuntimeException unavailable) {
            // Cleanup cannot authorize a query: the read path always checks DB expiry independently.
        }
    }

    private MapSqlParameterSource params(ChatQueryCursor cursor) {
        return new MapSqlParameterSource("id", cursor.snapshotId());
    }

    private ChatMessageResponse response(java.sql.ResultSet r) throws java.sql.SQLException {
        return new ChatMessageResponse(r.getLong("id"), r.getString("client_message_id"), r.getLong("room_id"),
                r.getLong("user_id"), r.getString("nickname"), r.getString("content"),
                r.getTimestamp("created_at").toLocalDateTime().atOffset(ZoneOffset.UTC));
    }
}
