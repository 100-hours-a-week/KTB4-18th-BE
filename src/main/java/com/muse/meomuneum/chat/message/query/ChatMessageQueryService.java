package com.muse.meomuneum.chat.message.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.connection.ChatSessionAccessPolicy;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.view.ChatViewInterval;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

@Service
public class ChatMessageQueryService {
    private DistributedChatMessageQueryService distributed;

    @Autowired(required = false)
    public void setDistributed(DistributedChatMessageQueryService service) {
        distributed = service;
    }

    private final ChatPresenceRegistry presence;
    private final ChatViewLifecycle views;
    private final ChatSessionAccessPolicy sessionAccess;
    private final NamedParameterJdbcTemplate jdbc;
    private final ChatCursorCodec cursors;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ChatMessageQueryService(ChatPresenceRegistry presence, ChatViewLifecycle views,
            NamedParameterJdbcTemplate jdbc, ChatCursorCodec cursors, Clock clock,
            PlatformTransactionManager manager, ChatSessionAccessPolicy sessionAccess) {
        this.presence = presence;
        this.views = views;
        this.sessionAccess = sessionAccess;
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> ready(String socketId, String viewId, long lastReceivedId) {
        if (distributed != null) {
            var context = views.context(socketId);
            return distributed.ready(context.userId(), context.roomId(), context.membershipId(), viewId,
                    lastReceivedId);
        }
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
        if (distributed != null) {
            return distributed.query(userId, roomId, membershipId, viewId, mode, token, size);
        }
        return presence.exclusive(() -> transactions.execute(status -> {
            boolean history = "history".equals(mode);
            List<ChatViewInterval> ranges = history
                    ? views.authorizeAccount(userId, roomId, membershipId, viewId).intervals()
                    : views.authorize(userId, roomId, membershipId, viewId);
            ChatQueryCursor cursor = token == null
                    ? initial(userId, roomId, membershipId, viewId, mode, snapshot(ranges), 0)
                    : cursors.decode(token);
            if (cursor.userId() != userId || cursor.roomId() != roomId || cursor.membershipId() != membershipId ||
                    !viewId.equals(cursor.viewId()) || !mode.equals(cursor.mode())) {
                throw new ChatQueryException(false);
            }
            MapSqlParameterSource params = parameters(roomId, ranges, cursor.snapshot());
            if (history) {
                params.addValue("now", LocalDateTime.parse(cursor.historyAsOf()));
            }
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
            return new ChatMessagePage(items, next, hasNext, cursor.snapshot(),
                    history
                            ? accountGaps(userId, roomId, ranges,
                                    LocalDateTime.parse(cursor.historyAsOf()))
                            : gaps(viewId, ranges));
        }));
    }

    public ChatMessageResponse byClientId(Long userId, Long roomId, Long membershipId, String viewId, String clientId) {
        if (distributed != null) {
            return distributed.byClientId(userId, roomId, membershipId, viewId, clientId);
        }
        try {
            if (!UUID.fromString(clientId).toString().equalsIgnoreCase(clientId)) {
                throw new ChatQueryException(false);
            }
        } catch (IllegalArgumentException invalid) {
            throw new ChatQueryException(false);
        }
        return presence.exclusive(() -> transactions.execute(status -> {
            List<ChatViewInterval> ranges = views.authorizeAccount(userId, roomId, membershipId, viewId).intervals();
            MapSqlParameterSource params = parameters(roomId, ranges, snapshot(ranges));
            params.addValue("user", userId).addValue("client", clientId.toLowerCase(Locale.ROOT));
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

    private ChatMessageResponse response(ResultSet row) throws SQLException {
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

    private List<ChatMessagePage.Gap> accountGaps(Long userId, Long roomId, List<ChatViewInterval> ranges,
            LocalDateTime asOf) {
        List<ChatMessagePage.Gap> result = new ArrayList<>();
        LocalDateTime cutoff = now().minusHours(24);
        LocalDateTime created = jdbc.queryForObject(
                "SELECT MIN(created_at) FROM chat_view_sessions WHERE user_id=:user AND room_id=:room",
                new MapSqlParameterSource("user", userId).addValue("room", roomId), LocalDateTime.class);
        if (created != null && !created.isAfter(cutoff)) {
            result.add(new ChatMessagePage.Gap("RETENTION", 0, cutoff.atOffset(ZoneOffset.UTC)));
        }
        LocalDateTime coveredUntil = null;
        Long coveredId = null;
        for (ChatViewInterval range : ranges) {
            if (range.startedAt().isAfter(asOf) || !range.endsAt().isAfter(cutoff)) {
                continue;
            }
            if (coveredUntil != null && (coveredUntil.isBefore(range.startedAt())
                    || (coveredUntil.equals(range.startedAt()) && coveredId != null
                            && range.startAfterMessageId() > coveredId))) {
                result.add(new ChatMessagePage.Gap("ABSENCE", range.startAfterMessageId(),
                        range.startedAt().atOffset(ZoneOffset.UTC)));
            }
            if (coveredUntil == null || range.endsAt().isAfter(coveredUntil)) {
                coveredUntil = range.endsAt();
                coveredId = range.endMessageId();
            } else if (range.endsAt().equals(coveredUntil) && coveredId != null) {
                coveredId = range.endMessageId() == null ? null : Math.max(coveredId, range.endMessageId());
            }
        }
        return List.copyOf(result);
    }

    public void authorizeSession(Long userId, String sid, Long roomId, Long membershipId) {
        if (!ChatAuthSessionRegistry.isValidSessionId(sid)) {
            throw new ChatRoomException(
                    ChatRoomErrorCode.AUTH_REQUIRED);
        }
        sessionAccess.authorize(userId, sid);
        Integer owned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_room_members
                WHERE id=:member AND user_id=:user AND room_id=:room AND auth_session_id=:sid
                    AND deleted_at IS NULL
                """, new MapSqlParameterSource("member", membershipId).addValue("user", userId)
                .addValue("room", roomId).addValue("sid", sid), Integer.class);
        if (owned == null || owned != 1) {
            throw new ChatRoomException(
                    ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND);
        }
    }

    private long snapshot(List<ChatViewInterval> ranges) {
        return ranges.stream().map(ChatViewInterval::endMessageId).filter(Objects::nonNull)
                .mapToLong(Long::longValue).max().orElse(0);
    }

    private ChatQueryCursor initial(long userId, long roomId, long membershipId, String viewId,
            String mode, long snapshot, long position) {
        return new ChatQueryCursor(userId, roomId, membershipId, viewId, mode, snapshot, position,
                clock.instant().plusSeconds(24 * 3600).toEpochMilli(),
                "history".equals(mode) ? now().toString() : null);
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
