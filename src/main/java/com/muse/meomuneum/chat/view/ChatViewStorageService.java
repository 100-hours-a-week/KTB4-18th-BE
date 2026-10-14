package com.muse.meomuneum.chat.view;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.chat.ban.service.ChatBanStorageService;
import com.muse.meomuneum.chat.member.domain.ChatRoomMember;
import com.muse.meomuneum.chat.member.repository.ChatRoomMemberRepository;
import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.user.repository.UserRepository;

/** Called under the single-server presence lock, including committed message and subscription transitions. */
@Service
public class ChatViewStorageService {
    private final JdbcTemplate jdbc;
    private final ChatRoomMemberRepository members;
    private final UserRepository users;
    private final ChatBanStorageService bans;
    private final Clock clock;
    private final String processId = UUID.randomUUID().toString();

    public ChatViewStorageService(JdbcTemplate jdbc, ChatRoomMemberRepository members, UserRepository users,
            ChatBanStorageService bans, Clock clock) {
        this.jdbc = jdbc;
        this.members = members;
        this.users = users;
        this.bans = bans;
        this.clock = clock;
    }

    @PostConstruct
    public void recoverAfterRestart() {
        // A dead process's open range has no provable disconnect time. Never invent a grace interval.
        jdbc.update("""
                UPDATE chat_view_intervals SET ends_at = verified_at,
                    end_message_id = verified_through_message_id, end_reason = 'RESTART'
                WHERE ends_at IS NULL AND process_id <> ?
                """, processId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void validateContext(Long userId, Long roomId, Long membershipId, String viewId) {
        authorizeMember(userId, roomId, membershipId);
        if (viewId != null) {
            requireView(userId, roomId, membershipId, viewId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String subscribed(Long userId, Long roomId, Long membershipId, String viewId) {
        validateContext(userId, roomId, membershipId, viewId);
        LocalDateTime now = now();
        if (viewId == null) {
            viewId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO chat_view_sessions (id, user_id, room_id, membership_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, viewId, userId, roomId, membershipId, now, now);
        }
        List<RangeState> ranges = latest(viewId);
        if (!ranges.isEmpty()) {
            RangeState last = ranges.getFirst();
            if (last.endsAt() == null) {
                // A continuing socket is handled by the lifecycle registry without reopening storage.
                // A new socket with no recorded disconnect cannot prove the intervening interval.
                jdbc.update("""
                        UPDATE chat_view_intervals SET ends_at = verified_at,
                            end_message_id = verified_through_message_id, end_reason = 'UNVERIFIED'
                        WHERE id = ?
                        """, last.id());
            } else if ("DISCONNECTED".equals(last.reason()) && last.endsAt().isAfter(now)) {
                jdbc.update("""
                        UPDATE chat_view_intervals SET ends_at = NULL, end_message_id = NULL,
                            end_reason = NULL, process_id = ?, verified_at = ?, verified_through_message_id = ?
                        WHERE id = ?
                        """, processId, now, watermark(roomId), last.id());
                touch(viewId, now);
                return viewId;
            }
        }
        long boundary = watermark(roomId);
        jdbc.update("""
                INSERT INTO chat_view_intervals
                    (view_session_id, process_id, started_at, start_after_message_id,
                     verified_at, verified_through_message_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, viewId, processId, now, boundary, now, boundary);
        touch(viewId, now);
        return viewId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void disconnected(String viewId) {
        jdbc.update("""
                UPDATE chat_view_intervals SET ends_at = ?, end_message_id = NULL, end_reason = 'DISCONNECTED'
                WHERE view_session_id = ? AND ends_at IS NULL AND process_id = ?
                """, now().plusSeconds(30), viewId, processId);
        touch(viewId, now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unsubscribed(String viewId, Long roomId) {
        closeRange(viewId, roomId, "UNSUBSCRIBED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ended(Long membershipId) {
        LocalDateTime now = now();
        List<ViewRoom> views = jdbc.query("SELECT id, room_id FROM chat_view_sessions WHERE membership_id = ?",
                (row, index) -> new ViewRoom(row.getString("id"), row.getLong("room_id")), membershipId);
        for (ViewRoom view : views) {
            closeRange(view.id(), view.roomId(), "LEFT");
        }
        jdbc.update("""
                UPDATE chat_view_sessions SET ended_at = ?, updated_at = ?
                WHERE membership_id = ? AND ended_at IS NULL
                """, now, now, membershipId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkpoint(String viewId, Long roomId) {
        LocalDateTime now = now();
        jdbc.update("""
                UPDATE chat_view_intervals SET verified_at = ?, verified_through_message_id = ?
                WHERE view_session_id = ? AND ends_at IS NULL AND process_id = ?
                """, now, watermark(roomId), viewId, processId);
        touch(viewId, now);
    }

    /** The lifecycle coordinator checks a current subscribed connection before calling this method. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ChatViewInterval> authorizedIntervals(Long userId, Long roomId, Long membershipId, String viewId) {
        validateContext(userId, roomId, membershipId, viewId);
        if (viewId == null) {
            throw denied();
        }
        checkpoint(viewId, roomId);
        LocalDateTime now = now();
        return jdbc.query("""
                SELECT id, start_after_message_id, started_at,
                    COALESCE(ends_at, verified_at) AS ends_at,
                    CASE WHEN ends_at IS NULL THEN verified_through_message_id ELSE end_message_id END AS end_id
                FROM chat_view_intervals
                WHERE view_session_id = ? AND COALESCE(ends_at, verified_at) > ?
                ORDER BY id
                """, (row, index) -> new ChatViewInterval(row.getLong("id"), row.getLong("start_after_message_id"),
                row.getTimestamp("started_at").toLocalDateTime(),
                row.getTimestamp("ends_at").toLocalDateTime(), row.getObject("end_id", Long.class)),
                viewId, now.minusHours(24));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredRecords() {
        LocalDateTime cutoff = now().minusHours(24);
        int deleted = jdbc.update("DELETE FROM chat_view_intervals WHERE ends_at <= ?", cutoff);
        deleted += jdbc.update("DELETE FROM chat_view_sessions WHERE updated_at <= ?", cutoff);
        return deleted;
    }

    private void closeRange(String viewId, Long roomId, String reason) {
        LocalDateTime now = now();
        jdbc.update("""
                UPDATE chat_view_intervals SET ends_at = ?, end_message_id = ?, end_reason = ?
                WHERE view_session_id = ? AND (ends_at IS NULL OR ends_at > ?)
                """, now, watermark(roomId), reason, viewId, now);
    }

    private void authorizeMember(Long userId, Long roomId, Long membershipId) {
        users.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.USER_NOT_FOUND));
        if (bans.hasActiveBan(userId)) {
            throw new ChatRoomException(ChatRoomErrorCode.CHAT_BANNED, bans.activeBanUntil(userId).orElse(null));
        }
        members.findByIdAndUser_IdAndChatRoom_Id(membershipId, userId, roomId)
                .filter(ChatRoomMember::isActive).filter(member -> member.getChatRoom().isActive())
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.MEMBERSHIP_NOT_FOUND));
    }

    private void requireView(Long userId, Long roomId, Long membershipId, String viewId) {
        try {
            if (!UUID.fromString(viewId).toString().equals(viewId)) {
                throw denied();
            }
        } catch (IllegalArgumentException invalid) {
            throw denied();
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_view_sessions
                WHERE id = ? AND user_id = ? AND room_id = ? AND membership_id = ?
                    AND ended_at IS NULL AND updated_at > ?
                """, Integer.class, viewId, userId, roomId, membershipId, now().minusHours(24));
        if (count == null || count != 1) {
            throw denied();
        }
    }

    private List<RangeState> latest(String viewId) {
        return jdbc.query("""
                SELECT id, ends_at, end_reason FROM chat_view_intervals
                WHERE view_session_id = ? ORDER BY id DESC LIMIT 1
                """, (row, index) -> new RangeState(row.getLong("id"),
                row.getTimestamp("ends_at") == null ? null : row.getTimestamp("ends_at").toLocalDateTime(),
                row.getString("end_reason")), viewId);
    }

    private long watermark(Long roomId) {
        // Physical message cleanup must not move a live view's id boundary backwards.
        return jdbc.queryForObject("""
                SELECT GREATEST(
                    (SELECT COALESCE(MAX(id), 0) FROM chat_room_messages WHERE room_id = ?),
                    (SELECT COALESCE(MAX(i.verified_through_message_id), 0)
                     FROM chat_view_intervals i JOIN chat_view_sessions v ON v.id = i.view_session_id
                     WHERE v.room_id = ?))
                """, Long.class, roomId, roomId);
    }

    private void touch(String viewId, LocalDateTime now) {
        jdbc.update("UPDATE chat_view_sessions SET updated_at = ? WHERE id = ? AND ended_at IS NULL", now, viewId);
    }

    private ChatRoomException denied() {
        return new ChatRoomException(ChatRoomErrorCode.CHAT_VIEW_UNAVAILABLE);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private record RangeState(long id, LocalDateTime endsAt, String reason) {
    }

    private record ViewRoom(String id, Long roomId) {
    }
}
