package com.muse.meomuneum.chat.message.query;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.view.ChatViewInterval;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

@Service
public class ChatMessageQueryService {
    private final ChatPresenceRegistry presence;
    private final ChatViewLifecycle views;
    private final NamedParameterJdbcTemplate jdbc;
    private final ChatCursorCodec cursors;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ChatMessageQueryService(ChatPresenceRegistry presence, ChatViewLifecycle views,
            NamedParameterJdbcTemplate jdbc, ChatCursorCodec cursors, Clock clock,
            PlatformTransactionManager manager) {
        this.presence = presence;
        this.views = views;
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> ready(String socketId, String viewId, long lastReceivedId) {
        return presence.exclusive(() -> transactions.execute(status -> {
            ChatViewLifecycle.Context context = views.context(socketId);
            List<ChatViewInterval> ranges = views.authorize(context.userId(), context.roomId(), context.membershipId(),
                    viewId);
            long snapshot = snapshot(ranges);
            ChatQueryCursor cursor = initial(context.userId(), context.roomId(), context.membershipId(), viewId,
                    "recovery", snapshot, Math.min(lastReceivedId, snapshot));
            return Map.of("room_id", context.roomId(), "membership_id", context.membershipId(),
                    "view_session_id", viewId, "recovery_cursor", cursors.encode(cursor),
                    "snapshot_message_id", snapshot);
        }));
    }

    public ChatMessagePage query(Long userId, Long roomId, Long membershipId, String viewId,
            String mode, String token, int size) {
        validateModeAndSize(mode, size);
        return presence.exclusive(() -> transactions.execute(status -> {
            List<ChatViewInterval> ranges = views.authorize(userId, roomId, membershipId, viewId);
            ChatQueryCursor cursor = token == null
                    ? initial(userId, roomId, membershipId, viewId, mode, snapshot(ranges), 0)
                    : cursors.decode(token);
            if (cursor.userId() != userId || cursor.roomId() != roomId || cursor.membershipId() != membershipId ||
                    !viewId.equals(cursor.viewId()) || !mode.equals(cursor.mode())) {
                throw new ChatQueryException(false);
            }
            MapSqlParameterSource params = parameters(roomId, ranges, cursor.snapshot());
            params.addValue("position", cursor.position()).addValue("limit", size + 1);
            String position = cursor.position() == 0
                    ? ""
                    : " AND m.id " + ("recovery".equals(mode) ? ">" : "<") + " :position";
            String order = "recovery".equals(mode) ? "ASC" : "DESC";
            List<ChatMessageResponse> loaded = jdbc.query(select() + permitted(ranges) + position +
                    " ORDER BY m.id " + order + " LIMIT :limit", params, (row, index) -> response(row));
            boolean hasNext = loaded.size() > size;
            List<ChatMessageResponse> items = List.copyOf(loaded.subList(0, Math.min(size, loaded.size())));
            String next = hasNext ? cursors.encode(cursor.next(items.getLast().messageId())) : null;
            return new ChatMessagePage(items, next, hasNext, cursor.snapshot(), gaps(viewId, ranges));
        }));
    }

    public ChatMessageResponse byClientId(Long userId, Long roomId, Long membershipId, String viewId, String clientId) {
        try {
            if (!UUID.fromString(clientId).toString().equalsIgnoreCase(clientId)) {
                throw new ChatQueryException(false);
            }
        } catch (IllegalArgumentException invalid) {
            throw new ChatQueryException(false);
        }
        return presence.exclusive(() -> transactions.execute(status -> {
            List<ChatViewInterval> ranges = views.authorize(userId, roomId, membershipId, viewId);
            MapSqlParameterSource params = parameters(roomId, ranges, snapshot(ranges));
            params.addValue("user", userId).addValue("client", clientId.toLowerCase(java.util.Locale.ROOT));
            List<ChatMessageResponse> found = jdbc.query(select() + permitted(ranges) +
                    " AND m.user_id = :user AND m.client_message_id = :client", params, (row, index) -> response(row));
            if (found.isEmpty()) {
                throw new ChatQueryException(true);
            }
            return found.getFirst();
        }));
    }

    private String select() {
        return "SELECT m.*, u.nickname FROM chat_room_messages m JOIN users u ON u.id = m.user_id " +
                "WHERE m.room_id = :room AND m.created_at > :cutoff AND m.created_at <= :now AND m.id <= :snapshot";
    }

    private String permitted(List<ChatViewInterval> ranges) {
        List<String> clauses = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            clauses.add("(m.id > :start" + i + " AND m.created_at >= :time" + i + " AND " +
                    (ranges.get(i).endMessageId() == null ? "m.created_at < :end" : "m.id <= :end") + i + ")");
        }
        return " AND (" + (clauses.isEmpty() ? "1 = 0" : String.join(" OR ", clauses)) + ")";
    }

    private MapSqlParameterSource parameters(Long roomId, List<ChatViewInterval> ranges, long snapshot) {
        LocalDateTime now = now();
        MapSqlParameterSource params = new MapSqlParameterSource("room", roomId)
                .addValue("cutoff", now.minusHours(24)).addValue("now", now).addValue("snapshot", snapshot);
        for (int i = 0; i < ranges.size(); i++) {
            ChatViewInterval range = ranges.get(i);
            params.addValue("start" + i, range.startAfterMessageId()).addValue("time" + i, range.startedAt())
                    .addValue("end" + i, range.endMessageId() == null ? range.endsAt() : range.endMessageId());
        }
        return params;
    }

    private ChatMessageResponse response(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ChatMessageResponse(row.getLong("id"), row.getString("client_message_id"), row.getLong("room_id"),
                row.getLong("user_id"), row.getString("nickname"), row.getString("content"),
                row.getTimestamp("created_at").toLocalDateTime().atOffset(ZoneOffset.UTC));
    }

    private List<ChatMessagePage.Gap> gaps(String viewId, List<ChatViewInterval> ranges) {
        List<ChatMessagePage.Gap> result = new ArrayList<>();
        LocalDateTime cutoff = now().minusHours(24);
        LocalDateTime created = jdbc.queryForObject("SELECT created_at FROM chat_view_sessions WHERE id = :view",
                new MapSqlParameterSource("view", viewId), LocalDateTime.class);
        if (created != null && !created.isAfter(cutoff)) {
            result.add(new ChatMessagePage.Gap("RETENTION", 0, cutoff.atOffset(ZoneOffset.UTC)));
        }
        for (int i = 1; i < ranges.size(); i++) {
            ChatViewInterval before = ranges.get(i - 1);
            ChatViewInterval after = ranges.get(i);
            if (before.endsAt().isBefore(after.startedAt())) {
                result.add(new ChatMessagePage.Gap("ABSENCE", after.startAfterMessageId(),
                        after.startedAt().atOffset(ZoneOffset.UTC)));
            }
        }
        return List.copyOf(result);
    }

    private long snapshot(List<ChatViewInterval> ranges) {
        return ranges.stream().map(ChatViewInterval::endMessageId).filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue).max().orElse(0);
    }

    private ChatQueryCursor initial(long userId, long roomId, long membershipId, String viewId,
            String mode, long snapshot, long position) {
        return new ChatQueryCursor(userId, roomId, membershipId, viewId, mode, snapshot, position,
                clock.instant().plusSeconds(24 * 3600).toEpochMilli());
    }

    private void validateModeAndSize(String mode, int size) {
        if (!("history".equals(mode) || "recovery".equals(mode)) || size < 1 || size > 50) {
            throw new ChatQueryException(false);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
