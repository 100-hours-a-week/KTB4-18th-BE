package com.muse.meomuneum.chat.view;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/** Every method runs under control/task/user/room locks held by ChatParticipationService. */
public final class ChatRoomReadBoundaryStore {
    private final JdbcTemplate jdbc;
    private final String environment;

    public ChatRoomReadBoundaryStore(JdbcTemplate jdbc, String environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    public void initialize(long room, long epoch) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT boundary_initialized FROM chat_room_runtime WHERE environment=? AND room_id=?
                """, Boolean.class, environment, room))) {
            return;
        }
        long base = sequence(room);
        jdbc.update("""
                INSERT INTO chat_message_order(environment,message_id,room_id,epoch,room_seq)
                SELECT ?,id,room_id,?,?+ROW_NUMBER() OVER (ORDER BY id)
                FROM chat_room_messages WHERE room_id=? ORDER BY id
                """, environment, epoch, base, room);
        jdbc.update("""
                UPDATE chat_room_runtime SET room_seq=GREATEST(room_seq,
                    (SELECT COALESCE(MAX(room_seq),0) FROM chat_message_order WHERE environment=? AND room_id=?)),
                    boundary_initialized=TRUE WHERE environment=? AND room_id=?
                """, environment, room, environment, room);
        jdbc.update("""
                UPDATE chat_view_intervals i JOIN chat_view_sessions v ON v.id=i.view_session_id
                LEFT JOIN chat_view_runtime r ON r.view_id=v.id
                SET i.environment=?,i.epoch=?,
                    i.start_after_room_seq=(SELECT COALESCE(MAX(o.room_seq),0) FROM chat_message_order o
                        WHERE o.environment=? AND o.room_id=? AND o.message_id<=i.start_after_message_id),
                    i.verified_through_room_seq=(SELECT COALESCE(MAX(o.room_seq),0) FROM chat_message_order o
                        WHERE o.environment=? AND o.room_id=? AND o.message_id<=i.verified_through_message_id),
                    i.end_room_seq=CASE WHEN i.end_message_id IS NULL THEN NULL ELSE
                        (SELECT COALESCE(MAX(o.room_seq),0) FROM chat_message_order o
                         WHERE o.environment=? AND o.room_id=? AND o.message_id<=i.end_message_id) END
                WHERE v.room_id=? AND i.start_after_room_seq IS NULL AND (r.environment IS NULL OR r.environment=?)
                """, environment, epoch, environment, room, environment, room, environment, room, room, environment);
    }

    public void subscribed(String view, long room, long epoch, String task, long generation) {
        List<Map<String, Object>> previous = jdbc.queryForList("""
                SELECT * FROM chat_view_intervals WHERE view_session_id=? ORDER BY id DESC LIMIT 1
                """, view);
        if (!previous.isEmpty()) {
            Map<String, Object> last = previous.getFirst();
            if ("DISCONNECTED".equals(last.get("end_reason"))
                    && jdbc.queryForObject("""
                            SELECT COUNT(*) FROM chat_view_intervals WHERE id=? AND ends_at>UTC_TIMESTAMP(6)
                                AND epoch=?
                            """, Integer.class, last.get("id"), epoch) == 1) {
                jdbc.update("""
                        UPDATE chat_view_intervals SET ends_at=NULL,end_message_id=NULL,end_room_seq=NULL,
                            end_reason=NULL,process_id=?,owner_task_id=?,owner_generation=?,
                            verified_at=UTC_TIMESTAMP(6),verified_through_message_id=?,verified_through_room_seq=?
                        WHERE id=?
                        """, task, task, generation, watermark(room), sequence(room), last.get("id"));
                touch(view);
                return;
            }
            closeUnverified(view);
        }
        jdbc.update(
                """
                        INSERT INTO chat_view_intervals(view_session_id,process_id,environment,epoch,
                            owner_task_id,owner_generation,
                            started_at,start_after_message_id,start_after_room_seq,verified_at,
                            verified_through_message_id,verified_through_room_seq)
                        VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(6),?,?,UTC_TIMESTAMP(6),?,?)
                        """,
                view, task, environment, epoch, task, generation, watermark(room), sequence(room), watermark(room),
                sequence(room));
        touch(view);
    }

    public void checkpoint(String view, long room, String task, long generation) {
        jdbc.update("""
                UPDATE chat_view_intervals SET verified_at=UTC_TIMESTAMP(6),verified_through_message_id=?,
                    verified_through_room_seq=? WHERE view_session_id=? AND ends_at IS NULL
                    AND owner_task_id=? AND owner_generation=?
                """, watermark(room), sequence(room), view, task, generation);
        touch(view);
    }

    public void disconnected(String view, String task, long generation) {
        jdbc.update("""
                UPDATE chat_view_intervals SET ends_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)),
                    end_message_id=NULL,end_room_seq=NULL,end_reason='DISCONNECTED'
                WHERE view_session_id=? AND ends_at IS NULL AND owner_task_id=? AND owner_generation=?
                """, view, task, generation);
        touch(view);
    }

    public void close(String view, long room, String reason) {
        // A dead/unsubscribed owner cannot establish an unobserved reading interval up to now.
        jdbc.update("""
                UPDATE chat_view_intervals i JOIN chat_view_runtime v ON v.view_id=i.view_session_id
                LEFT JOIN chat_runtime_tasks t ON t.environment=v.environment AND t.task_id=v.owner_task_id
                SET i.ends_at=i.verified_at,i.end_message_id=i.verified_through_message_id,
                    i.end_room_seq=i.verified_through_room_seq,i.end_reason='UNVERIFIED'
                WHERE i.view_session_id=? AND i.ends_at IS NULL
                    AND (v.state<>'CONNECTED' OR t.state<>'ACTIVE' OR t.lease_until<=UTC_TIMESTAMP(6)
                         OR t.task_id IS NULL)
                """, view);
        jdbc.update("""
                UPDATE chat_view_intervals SET ends_at=UTC_TIMESTAMP(6),end_message_id=?,end_room_seq=?,end_reason=?
                WHERE view_session_id=? AND (ends_at IS NULL OR ends_at>UTC_TIMESTAMP(6))
                """, watermark(room), sequence(room), reason, view);
    }

    public void closeUnverified(String view) {
        jdbc.update("""
                UPDATE chat_view_intervals SET ends_at=verified_at,end_message_id=verified_through_message_id,
                    end_room_seq=verified_through_room_seq,end_reason='UNVERIFIED'
                WHERE view_session_id=? AND ends_at IS NULL
                """, view);
    }

    public void recordMessage(long message, long room, long epoch) {
        jdbc.update("INSERT INTO chat_message_order(environment,message_id,room_id,epoch,room_seq) VALUES (?,?,?,?,?)",
                environment, message, room, epoch, sequence(room));
    }

    public long watermark(long room) {
        return jdbc.queryForObject("""
                SELECT GREATEST(
                    (SELECT COALESCE(MAX(message_id),0) FROM chat_message_order WHERE environment=? AND room_id=?),
                    (SELECT COALESCE(MAX(i.verified_through_message_id),0) FROM chat_view_intervals i
                        JOIN chat_view_sessions v ON v.id=i.view_session_id WHERE i.environment=? AND v.room_id=?))
                """, Long.class, environment, room, environment, room);
    }

    public long sequence(long room) {
        return jdbc.queryForObject("SELECT room_seq FROM chat_room_runtime WHERE environment=? AND room_id=?",
                Long.class, environment, room);
    }

    private void touch(String view) {
        jdbc.update("UPDATE chat_view_sessions SET updated_at=UTC_TIMESTAMP(6) WHERE id=? AND ended_at IS NULL", view);
    }
}
