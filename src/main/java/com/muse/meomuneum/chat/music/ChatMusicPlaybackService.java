package com.muse.meomuneum.chat.music;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.music.ChatMusicQueueService.Caller;
import com.muse.meomuneum.chat.music.ChatMusicQueueService.Result;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationService.MusicTransaction;

import tools.jackson.databind.ObjectMapper;

/** The database clock and pinned source define playback; no browser ENDED notification is trusted. */
public final class ChatMusicPlaybackService {
    private static final long MAX_NUMBER = 9_007_199_254_740_991L;
    private final JdbcTemplate jdbc;
    private final ChatMusicStateRepository states;
    private final ChatMusicCommandStore commands;
    private final ObjectMapper json = new ObjectMapper();

    public ChatMusicPlaybackService(JdbcTemplate jdbc, ChatMusicStateRepository states,
            ChatMusicCommandStore commands) {
        this.jdbc = jdbc;
        this.states = states;
        this.commands = commands;
    }

    public void validateStorage() {
        if (!jdbc.queryForList("""
                SELECT chat_room_id FROM playback_states WHERE state<>'IDLE' AND
                  (source_video_id IS NULL OR duration_millis IS NULL OR (state='PLAYING' AND due_at IS NULL)) LIMIT 1
                """, Long.class).isEmpty()) {
            throw new IllegalStateException("Playback activation requires consistent pinned active state");
        }
    }

    public Result control(ChatParticipationService participation, Caller c, String id, String action,
            Long currentItem, long expectedVersion) {
        ChatMusicQueueService.uuid(id);
        if (action == null || !List.of("PLAY", "PAUSE", "RESUME", "SKIP").contains(action)
                || expectedVersion < 0 || expectedVersion > MAX_NUMBER
                || (currentItem != null && (currentItem <= 0 || currentItem > MAX_NUMBER))) {
            throw new ChatMusicException(400, "INVALID_PLAYBACK_COMMAND");
        }
        return participation.musicTransaction(c.userId(), c.sid(), c.roomId(), c.membershipId(), c.viewId(), tx -> {
            Snapshot state = lock(c.roomId());
            long target = currentItem == null ? 0 : currentItem;
            Result replay = commands.replay(c, tx, id, action, target, expectedVersion);
            if (replay != null) {
                return replay;
            }
            commands.limit(c, tx);
            if (state.version() != expectedVersion || !Objects.equals(state.item(), currentItem)) {
                throw new ChatMusicException(409, "VERSION_CONFLICT");
            }
            Change change = switch (action) {
                case "PLAY" -> state.state().equals("IDLE")
                        ? start(tx.roomId(), state.now())
                        : state.state().equals("PLAYING") ? Change.NONE : invalidState();
                case "PAUSE" -> pause(tx.roomId(), state);
                case "RESUME" -> resume(tx.roomId(), state);
                case "SKIP" -> skip(tx.roomId(), state);
                default -> throw new ChatMusicException(400, "INVALID_PLAYBACK_COMMAND");
            };
            if (action.equals("PLAY") && !change.changed() && state.state().equals("IDLE")) {
                throw new ChatMusicException(409, "QUEUE_EMPTY");
            }
            publish(tx, change, action);
            Map<String, Object> snapshot = snapshot(tx.roomId());
            Result result = new Result(200, json.writeValueAsString(Map.of("message", "playback command completed",
                    "data", snapshot)));
            commands.complete(c, tx, id, action, target, expectedVersion,
                    ((Number) snapshot.get("version")).longValue(), result);
            return result;
        });
    }

    /** Combined with ADD's single aggregate version and receipt, never a second transaction. */
    public Change afterAdd(MusicTransaction tx) {
        return advance(tx);
    }

    public void reconcile(MusicTransaction tx) {
        publish(tx, advance(tx), "AUTOMATIC");
    }

    public void presenceChanged(MusicTransaction tx) {
        if (!hasPotentialParticipants(tx) && !hasRecoverableParticipants(tx)) {
            publish(tx, clear(tx.roomId(), lock(tx.roomId())), "ROOM_EMPTY");
        }
    }

    private Change advance(MusicTransaction tx) {
        Snapshot s = lock(tx.roomId());
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_rooms r JOIN regions g ON g.id=r.region_id
                JOIN regions p ON p.id=g.parent_id WHERE r.id=? AND r.status='ACTIVE'
                  AND g.is_active=TRUE AND p.is_active=TRUE
                """, Long.class, tx.roomId()) != 1) {
            return Change.NONE;
        }
        if (!hasPotentialParticipants(tx)) {
            return hasRecoverableParticipants(tx) ? Change.NONE : clear(tx.roomId(), s);
        }
        if (s.state().equals("PLAYING") && !s.due().isAfter(s.now())) {
            finish(tx.roomId(), s, "PLAYED");
            idle(tx.roomId(), s.now());
            return Change.BOTH.merge(start(tx.roomId(), s.now()));
        }
        return s.state().equals("IDLE") ? start(tx.roomId(), s.now()) : Change.NONE;
    }

    private Change pause(long room, Snapshot s) {
        if (s.state().equals("PAUSED")) {
            return Change.NONE;
        }
        if (!s.state().equals("PLAYING")) {
            return invalidState();
        }
        if (!s.due().isAfter(s.now())) {
            throw new ChatMusicException(409, "TRACK_FINISHED");
        }
        jdbc.update("""
                UPDATE playback_states SET state='PAUSED',position_seconds=?,state_changed_at=?,due_at=NULL
                WHERE chat_room_id=?
                """, BigDecimal.valueOf(positionMillis(s), 3), s.now(), room);
        return Change.PLAYBACK;
    }

    private Change resume(long room, Snapshot s) {
        if (s.state().equals("PLAYING")) {
            return Change.NONE;
        }
        if (!s.state().equals("PAUSED")) {
            return invalidState();
        }
        long music = jdbc.queryForObject("SELECT music_id FROM music_queue_items WHERE room_id=? AND id=?",
                Long.class, room, s.item());
        var source = reviewedSource(music);
        if (source == null || !source.video().equals(s.video())) {
            throw new ChatMusicException(409, "MUSIC_UNAVAILABLE");
        }
        long remaining = s.duration() - positionMillis(s);
        jdbc.update("""
                UPDATE playback_states SET state='PLAYING',state_changed_at=?,due_at=? WHERE chat_room_id=?
                """, s.now(), s.now().plusNanos(remaining * 1_000_000L), room);
        return Change.PLAYBACK;
    }

    private Change skip(long room, Snapshot s) {
        if (s.item() == null) {
            return invalidState();
        }
        finish(room, s, s.state().equals("PLAYING") && !s.due().isAfter(s.now()) ? "PLAYED" : "SKIPPED");
        idle(room, s.now());
        return Change.BOTH.merge(start(room, s.now()));
    }

    private Change start(long room, LocalDateTime now) {
        var items = jdbc.queryForList("""
                SELECT id,music_id FROM music_queue_items WHERE room_id=? AND status='WAITING'
                ORDER BY sequence_no LIMIT 20 FOR UPDATE
                """, room);
        Change change = Change.NONE;
        for (var item : items) {
            long id = ((Number) item.get("id")).longValue();
            Source source = reviewedSource(((Number) item.get("music_id")).longValue());
            if (source == null) {
                jdbc.update("UPDATE music_queue_items SET status='CANCELED',finished_at=? WHERE id=? AND room_id=?",
                        now, id, room);
                change = change.merge(Change.QUEUE);
                continue;
            }
            jdbc.update("UPDATE music_queue_items SET status='PLAYING',started_at=? WHERE id=? AND room_id=?",
                    now, id, room);
            jdbc.update("""
                    UPDATE playback_states SET current_queue_item_id=?,state='PLAYING',position_seconds=0,
                      state_changed_at=?,source_video_id=?,duration_millis=?,due_at=? WHERE chat_room_id=?
                    """, id, now, source.video(), source.duration(), now.plusNanos(source.duration() * 1_000_000L),
                    room);
            return Change.BOTH;
        }
        return change;
    }

    private Source reviewedSource(long music) {
        // Admission provenance is not re-evaluated after a successful ADD; catalog registration shares this lock.
        jdbc.queryForList("SELECT id FROM music WHERE id=? FOR SHARE", music);
        var sources = jdbc.query("""
                SELECT ps.youtube_video_id,ps.duration_millis FROM music m
                JOIN music_playback_sources ps ON ps.music_id=m.id
                WHERE m.id=? AND BINARY ps.youtube_video_id=BINARY m.youtube_video_id AND ps.status='VERIFIED'
                  AND ps.playback_country='KR' AND ps.verified_at<=UTC_TIMESTAMP(6)
                  AND ps.valid_until>UTC_TIMESTAMP(6) AND ps.duration_millis BETWEEN 1 AND 9999999999
                """, (r, i) -> new Source(r.getString(1), r.getLong(2)), music);
        return sources.isEmpty() ? null : sources.getFirst();
    }

    private void finish(long room, Snapshot s, String status) {
        if (jdbc.update(
                "UPDATE music_queue_items SET status=?,finished_at=? WHERE room_id=? AND id=? AND status='PLAYING'",
                status, s.now(), room, s.item()) != 1) {
            throw new IllegalStateException("Current playback item is inconsistent");
        }
    }

    private void idle(long room, LocalDateTime now) {
        jdbc.update("""
                UPDATE playback_states SET current_queue_item_id=NULL,state='IDLE',position_seconds=0,
                  state_changed_at=?,source_video_id=NULL,duration_millis=NULL,due_at=NULL WHERE chat_room_id=?
                """, now, room);
    }

    private Change clear(long room, Snapshot s) {
        int canceled = jdbc.update("""
                UPDATE music_queue_items SET status='CANCELED',finished_at=?
                WHERE room_id=? AND status IN ('WAITING','PLAYING')
                """, s.now(), room);
        boolean playback = !s.state().equals("IDLE");
        if (playback) {
            idle(room, s.now());
        }
        return new Change(canceled > 0, playback);
    }

    public void publish(MusicTransaction tx, Change change, String reason) {
        if (!change.changed()) {
            return;
        }
        long previous = jdbc.queryForObject("SELECT version FROM playback_states WHERE chat_room_id=?",
                Long.class, tx.roomId());
        if (previous >= MAX_NUMBER) {
            throw new ChatMusicException(409, "MUSIC_COUNTER_EXHAUSTED");
        }
        jdbc.update("UPDATE playback_states SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE chat_room_id=?",
                tx.roomId());
        events(tx, previous + 1, change, reason);
    }

    public void events(MusicTransaction tx, long version, Change change, String reason) {
        Map<String, Object> data = Map.of("reason", reason, "playback", snapshot(tx.roomId()));
        if (change.queue()) {
            commands.event(tx, version, "QUEUE_UPDATED", data);
        }
        if (change.playback()) {
            commands.event(tx, version, "PLAYBACK_UPDATED", data);
        }
    }

    private Snapshot lock(long room) {
        states.lockRoom(room);
        Snapshot s = jdbc.queryForObject("""
                SELECT current_queue_item_id,state,position_seconds,state_changed_at,version,source_video_id,
                  duration_millis,due_at,UTC_TIMESTAMP(6) clock FROM playback_states WHERE chat_room_id=?
                """, (r, i) -> new Snapshot(r.getObject(1, Long.class), r.getString(2), r.getBigDecimal(3),
                r.getObject(4, LocalDateTime.class), r.getLong(5), r.getString(6), r.getObject(7, Long.class),
                r.getObject(8, LocalDateTime.class), r.getObject(9, LocalDateTime.class)), room);
        if (!s.state().equals("IDLE") && (s.video() == null || s.duration() == null
                || (s.state().equals("PLAYING") && s.due() == null))) {
            throw new IllegalStateException("Active playback requires a pinned source and deadline");
        }
        return s;
    }

    public Map<String, Object> snapshot(long room) {
        Snapshot s = lock(room);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", s.state());
        result.put("version", s.version());
        result.put("current_queue_item_id", s.item());
        result.put("position_seconds", s.position());
        result.put("estimated_position_seconds", BigDecimal.valueOf(positionMillis(s), 3));
        result.put("state_changed_at", utc(s.changed()));
        result.put("server_time", utc(s.now()));
        result.put("source_video_id", s.video());
        result.put("duration_millis", s.duration());
        result.put("due_at", s.due() == null ? null : utc(s.due()));
        var current = s.item() == null ? null : jdbc.queryForMap("""
                SELECT q.id queue_item_id,q.music_id,q.added_by_user_id,q.sequence_no,q.status,
                  m.title,m.artist_name,m.album_cover_url FROM music_queue_items q JOIN music m ON m.id=q.music_id
                WHERE q.room_id=? AND q.id=?
                """, room, s.item());
        if (current != null) {
            current.put("youtube_video_id", s.video());
            current.put("duration_millis", s.duration());
        }
        result.put("current", current);
        return result;
    }

    private long positionMillis(Snapshot s) {
        if (s.duration() == null) {
            return 0;
        }
        long elapsed = s.state().equals("PLAYING") ? Math.max(0, Duration.between(s.changed(), s.now()).toMillis()) : 0;
        return Math.min(s.duration(), s.position().movePointRight(3).longValueExact() + elapsed);
    }

    private boolean hasPotentialParticipants(MusicTransaction tx) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_view_runtime v JOIN chat_view_sessions s ON s.id=v.view_id
                JOIN chat_room_members m ON m.id=v.membership_id JOIN users u ON u.id=v.user_id
                LEFT JOIN chat_runtime_tasks t ON t.environment=v.environment AND t.task_id=v.owner_task_id
                WHERE m.deleted_at IS NULL AND s.ended_at IS NULL AND u.deleted_at IS NULL
                  AND s.updated_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))
                  AND NOT EXISTS(SELECT 1 FROM chat_bans b WHERE b.user_id=v.user_id AND b.deleted_at IS NULL
                    AND b.created_at<=UTC_TIMESTAMP(6) AND b.expires_at>UTC_TIMESTAMP(6))
                  AND v.environment=? AND v.epoch=? AND v.room_id=?
                  AND (v.owner_task_id IS NULL OR
                    (t.epoch=v.epoch AND t.state='ACTIVE' AND t.lease_until>UTC_TIMESTAMP(6)))
                  AND (v.state='CONNECTED' OR
                    (v.state IN ('RESERVED','GRACE') AND v.deadline>UTC_TIMESTAMP(6)))
                """, Long.class, tx.context().environment(), tx.context().epoch(), tx.roomId()) > 0;
    }

    /** Failure recovery never manufactures a new grace period or a reading entitlement. */
    private boolean hasRecoverableParticipants(MusicTransaction tx) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_view_runtime v JOIN chat_view_sessions s ON s.id=v.view_id
                JOIN chat_room_members m ON m.id=v.membership_id JOIN users u ON u.id=v.user_id
                LEFT JOIN chat_runtime_tasks t ON t.environment=v.environment AND t.task_id=v.owner_task_id
                WHERE v.environment=? AND v.epoch=? AND v.room_id=?
                  AND m.deleted_at IS NULL AND s.ended_at IS NULL AND u.deleted_at IS NULL
                  AND s.updated_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))
                  AND NOT EXISTS(SELECT 1 FROM chat_bans b WHERE b.user_id=v.user_id AND b.deleted_at IS NULL
                    AND b.created_at<=UTC_TIMESTAMP(6) AND b.expires_at>UTC_TIMESTAMP(6))
                  AND (v.state='OFFLINE' OR (v.state IN ('CONNECTED','RESERVED','GRACE')
                    AND v.owner_task_id IS NOT NULL
                    AND (t.task_id IS NULL OR t.epoch<>v.epoch OR t.state<>'ACTIVE'
                      OR t.lease_until<=UTC_TIMESTAMP(6))))
                  AND EXISTS(SELECT 1 FROM chat_view_intervals i WHERE i.view_session_id=v.view_id
                    AND i.verified_at>TIMESTAMPADD(SECOND,-30,UTC_TIMESTAMP(6)))
                """, Long.class, tx.context().environment(), tx.context().epoch(), tx.roomId()) > 0;
    }

    private String utc(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toString();
    }

    private Change invalidState() {
        throw new ChatMusicException(409, "INVALID_PLAYBACK_STATE");
    }

    private record Source(String video, long duration) {
    }

    private record Snapshot(Long item, String state, BigDecimal position, LocalDateTime changed, long version,
            String video, Long duration, LocalDateTime due, LocalDateTime now) {
    }

    public record Change(boolean queue, boolean playback) {
        public static final Change NONE = new Change(false, false);
        public static final Change QUEUE = new Change(true, false);
        public static final Change PLAYBACK = new Change(false, true);
        public static final Change BOTH = new Change(true, true);

        public boolean changed() {
            return queue || playback;
        }

        public Change merge(Change other) {
            return new Change(queue || other.queue, playback || other.playback);
        }
    }
}
