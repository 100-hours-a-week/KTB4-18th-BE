package com.muse.meomuneum.chat.music;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.music.ChatMusicQueueService.Caller;
import com.muse.meomuneum.chat.music.ChatMusicQueueService.Result;
import com.muse.meomuneum.chat.participation.ChatParticipationService.MusicTransaction;

import tools.jackson.databind.ObjectMapper;

/** Shared receipt contract across queue and playback commands; caller already holds account and room locks. */
public final class ChatMusicCommandStore {
    private final JdbcTemplate jdbc;
    private final ChatMusicKeys keys;
    private final int intervalMillis;
    private final ObjectMapper json = new ObjectMapper();

    public ChatMusicCommandStore(JdbcTemplate jdbc, ChatMusicKeys keys, int intervalMillis) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.intervalMillis = intervalMillis;
    }

    private String fingerprintInput(Caller c, MusicTransaction tx, String id, String type, long target, long version) {
        return "command|" + tx.context().environment() + "|" + c.userId() + "|" + c.roomId() + "|" + id + "|" + c.sid()
                + "|" + c.membershipId() + "|" + c.viewId() + "|" + type + "|" + target + "|" + version;
    }

    public Result replay(Caller c, MusicTransaction tx, String id, String type, long target, long version) {
        return replay(c, tx, id, type, target, version, null);
    }

    public Result replay(Caller c, MusicTransaction tx, String id, String type, long target, long version,
            String candidate) {
        var rows = jdbc.query("""
                SELECT key_id,fingerprint,http_status,result_body,expires_at FROM chat_music_command_receipts
                WHERE environment=? AND user_id=? AND room_id=? AND command_id=?
                """, (r, i) -> new Receipt(r.getString(1), r.getString(2), r.getInt(3), r.getString(4),
                r.getObject(5, LocalDateTime.class)), tx.context().environment(), c.userId(), c.roomId(), id);
        if (rows.isEmpty()) {
            return null;
        }
        var old = rows.getFirst();
        if (!keys.matches(old.key(), old.fingerprint(), fingerprintInput(c, tx, id, type, target, version)
                + (candidate == null ? "" : "|candidate|" + candidate))) {
            throw new ChatMusicException(409, "COMMAND_ID_CONFLICT");
        }
        if (!old.expiry().isAfter(tx.context().asOf())) {
            throw new ChatMusicException(410, "COMMAND_EXPIRED");
        }
        return new Result(old.status(), old.body());
    }

    public void limit(Caller c, MusicTransaction tx) {
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_music_command_receipts WHERE environment=? AND user_id=?
                  AND completed_at>TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6))
                """, Long.class, tx.context().environment(), c.userId(), -intervalMillis * 1000L) > 0) {
            throw new ChatMusicException(429, "COMMAND_RATE_LIMITED", Math.max(1, (intervalMillis + 999) / 1000));
        }
    }

    public void complete(Caller c, MusicTransaction tx, String id, String type, long target, long expectedVersion,
            long resultVersion, Result result) {
        complete(c, tx, id, type, target, expectedVersion, resultVersion, result, null);
    }

    public void complete(Caller c, MusicTransaction tx, String id, String type, long target, long expectedVersion,
            long resultVersion, Result result, String candidate) {
        jdbc.update("""
                INSERT INTO chat_music_command_receipts(environment,user_id,room_id,command_id,auth_session_id,
                  membership_id,view_id,command_type,key_id,fingerprint,result_version,http_status,
                  result_body,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(6)))
                """, tx.context().environment(), c.userId(), c.roomId(), id, c.sid(), c.membershipId(), c.viewId(),
                type,
                keys.activeId(),
                keys.digest(keys.activeId(), fingerprintInput(c, tx, id, type, target, expectedVersion)
                        + (candidate == null ? "" : "|candidate|" + candidate)),
                resultVersion, result.status(), result.body());
    }

    public void event(MusicTransaction tx, long version, String type, Map<String, Object> data) {
        String id = UUID.randomUUID().toString();
        String body = json.writeValueAsString(Map.of("event_id", id, "environment", tx.context().environment(),
                "epoch", tx.context().epoch(), "producer_id", tx.producerId(), "room_id", tx.roomId(),
                "room_seq", tx.changed(), "music_version", version, "type", type, "data", data,
                "occurred_at", tx.context().asOf().toInstant(ZoneOffset.UTC).toString()));
        jdbc.update("""
                INSERT INTO chat_music_outbox(event_id,environment,epoch,producer_id,room_id,room_seq,
                  music_version,event_type,event_body,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(6)))
                """, id, tx.context().environment(), tx.context().epoch(), tx.producerId(), tx.roomId(), tx.changed(),
                version, type, body);
    }

    private record Receipt(String key, String fingerprint, int status, String body, LocalDateTime expiry) {
    }
}
