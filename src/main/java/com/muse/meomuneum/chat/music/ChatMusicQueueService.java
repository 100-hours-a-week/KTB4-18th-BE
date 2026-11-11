package com.muse.meomuneum.chat.music;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.music.catalog.RequestableMusicRepository;

import tools.jackson.databind.ObjectMapper;

public class ChatMusicQueueService {
    private static final long MAX_NUMBER = 9_007_199_254_740_991L;
    private final JdbcTemplate jdbc;
    private final ChatParticipationService participation;
    private final ChatMusicStateRepository states;
    private final RequestableMusicRepository catalog;
    private final ChatMusicKeys keys;
    private final ChatMusicCursor cursors;
    private final ObjectMapper json = new ObjectMapper();
    private final int capacity;
    private final ChatMusicCommandStore commands;
    private ChatMusicPlaybackService playback;

    public ChatMusicQueueService(JdbcTemplate jdbc, ChatParticipationService participation,
            ChatMusicStateRepository states, RequestableMusicRepository catalog, ChatMusicKeys keys,
            int capacity, int commandIntervalMillis) {
        if (capacity < 1 || capacity > 1000 || commandIntervalMillis < 0 || commandIntervalMillis > 60_000) {
            throw new IllegalArgumentException("Invalid music queue limits");
        }
        this.jdbc = jdbc;
        this.participation = participation;
        this.states = states;
        this.catalog = catalog;
        this.keys = keys;
        this.cursors = new ChatMusicCursor(keys);
        this.capacity = capacity;
        this.commands = new ChatMusicCommandStore(jdbc, keys, commandIntervalMillis);
    }

    public void setPlaybackService(ChatMusicPlaybackService service) {
        playback = service;
    }

    public void validateRetainedKeys(String environment) {
        List<String> retained = jdbc.queryForList(
                "SELECT DISTINCT key_id FROM chat_music_command_receipts WHERE environment=?", String.class,
                environment);
        if (!keys.keyIds().containsAll(retained)) {
            throw new IllegalStateException("Retained music receipt keys are required, including expired tombstones");
        }
    }

    public record Caller(long userId, String sid, long roomId, long membershipId, String viewId) {
        public Caller {
            if (userId <= 0 || roomId <= 0 || membershipId <= 0
                    || userId > MAX_NUMBER || roomId > MAX_NUMBER || membershipId > MAX_NUMBER) {
                throw new ChatMusicException(400, "INVALID_PARTICIPATION");
            }
            uuid(sid);
            uuid(viewId);
        }
    }

    public record Result(int status, String body) {
    }

    public Map<String, Object> candidates(Caller caller, String query, int size, String cursor) {
        checkSize(size);
        String normalized = RequestableMusicRepository.normalizeQuery(query);
        if (normalized.length() > 100) {
            throw new ChatMusicException(400, "INVALID_QUERY");
        }
        return participation.musicTransaction(caller.userId(), caller.sid(), caller.roomId(), caller.membershipId(),
                caller.viewId(), tx -> {
                    long now = tx.context().asOf().toEpochSecond(ZoneOffset.UTC);
                    String scope = scope(caller, tx.context().environment(), "search:" + normalized, size);
                    var page = cursors.decode(cursor, scope, now);
                    long region = jdbc.queryForObject("SELECT region_id FROM chat_rooms WHERE id=?", Long.class,
                            caller.roomId());
                    var items = catalog.search(caller.userId(), region, normalized, page.last(), size + 1);
                    return paginated(items, size, scope, -1, now, "music_id");
                });
    }

    public Map<String, Object> queue(Caller caller, int size, String cursor) {
        checkSize(size);
        return participation.musicTransaction(caller.userId(), caller.sid(), caller.roomId(), caller.membershipId(),
                caller.viewId(), tx -> {
                    if (playback != null) {
                        playback.reconcile(tx);
                    }
                    var state = states.lockRoom(caller.roomId());
                    long now = tx.context().asOf().toEpochSecond(ZoneOffset.UTC);
                    String scope = scope(caller, tx.context().environment(), "queue", size);
                    var page = cursors.decode(cursor, scope, now);
                    if (page.version() != -1 && page.version() != state.version()) {
                        throw new ChatMusicException(409, "QUEUE_CHANGED");
                    }
                    var items = jdbc.queryForList("""
                            SELECT q.id queue_item_id,q.music_id,q.added_by_user_id,q.sequence_no,q.status,
                              m.title,m.artist_name,m.album_cover_url,m.youtube_video_id
                            FROM music_queue_items q JOIN music m ON m.id=q.music_id
                            WHERE q.room_id=? AND q.status='WAITING' AND q.sequence_no>? ORDER BY q.sequence_no LIMIT ?
                            """, caller.roomId(), page.last(), size + 1);
                    var result = paginated(items, size, scope, state.version(), now, "sequence_no");
                    result.put("epoch", tx.context().epoch());
                    result.put("version", state.version());
                    result.put("state", state.state());
                    result.put("position_seconds", state.positionSeconds());
                    result.put("state_changed_at", state.stateChangedAt().toString());
                    result.put("server_time", tx.context().asOf().toInstant(ZoneOffset.UTC).toString());
                    result.put("current",
                            state.queueItemId() == null ? null : current(caller.roomId(), state.queueItemId()));
                    if (playback != null) {
                        result.putAll(playback.snapshot(caller.roomId()));
                    }
                    return result;
                });
    }

    private Map<String, Object> current(long room, long item) {
        return jdbc.queryForMap("""
                SELECT q.id queue_item_id,q.music_id,q.added_by_user_id,q.sequence_no,q.status,
                  m.title,m.artist_name,m.album_cover_url,m.youtube_video_id
                FROM music_queue_items q JOIN music m ON m.id=q.music_id WHERE q.room_id=? AND q.id=?
                """, room, item);
    }

    public Result add(Caller caller, String commandId, long musicId) {
        positive(musicId);
        return command(caller, commandId, "ADD", musicId, -1);
    }

    public Result cancel(Caller caller, String commandId, long itemId, long expectedVersion) {
        positive(itemId);
        if (expectedVersion < 0 || expectedVersion > MAX_NUMBER) {
            throw new ChatMusicException(400, "INVALID_VERSION");
        }
        return command(caller, commandId, "CANCEL", itemId, expectedVersion);
    }

    private Result command(Caller c, String commandId, String type, long target, long expectedVersion) {
        uuid(commandId);
        return participation.musicTransaction(c.userId(), c.sid(), c.roomId(), c.membershipId(), c.viewId(), tx -> {
            var context = tx.context();
            var state = states.lockRoom(c.roomId());
            Result replay = commands.replay(c, tx, commandId, type, target, expectedVersion);
            if (replay != null) {
                return replay;
            }
            commands.limit(c, tx);
            if (state.version() >= MAX_NUMBER || (type.equals("ADD") && state.nextSequenceNo() >= MAX_NUMBER)) {
                throw new ChatMusicException(409, "MUSIC_COUNTER_EXHAUSTED");
            }
            long item;
            if (type.equals("ADD")) {
                item = append(c, target, state.nextSequenceNo());
            } else {
                if (state.version() != expectedVersion) {
                    throw new ChatMusicException(409, "VERSION_CONFLICT");
                }
                if (jdbc.update("""
                        UPDATE music_queue_items SET status='CANCELED',finished_at=UTC_TIMESTAMP(6)
                        WHERE id=? AND room_id=? AND status='WAITING'
                        """, target, c.roomId()) != 1) {
                    throw new ChatMusicException(409, "QUEUE_ITEM_NOT_WAITING");
                }
                item = target;
            }
            var playbackChange = playback != null && type.equals("ADD")
                    ? playback.afterAdd(tx)
                    : ChatMusicPlaybackService.Change.NONE;
            long version = state.version() + 1;
            jdbc.update("""
                    UPDATE playback_states SET version=?,next_sequence_no=?,updated_at=UTC_TIMESTAMP(6)
                    WHERE chat_room_id=?
                    """, version, state.nextSequenceNo() + (type.equals("ADD") ? 1 : 0), c.roomId());
            tx.changed();
            int status = type.equals("ADD") ? 201 : 204;
            Map<String, Object> data = new LinkedHashMap<>();
            if (type.equals("ADD")) {
                data.put("queue_item_id", item);
                data.put("music_id", target);
                data.put("sequence_no", state.nextSequenceNo());
                data.put("status", jdbc.queryForObject("SELECT status FROM music_queue_items WHERE id=?",
                        String.class, item));
                data.put("version", version);
                if (playback != null) {
                    data.put("playback", playback.snapshot(c.roomId()));
                }
            }
            String body = type.equals("ADD")
                    ? json.writeValueAsString(Map.of("message", "music queued", "data", data))
                    : "{}";
            Result result = new Result(status, body);
            commands.complete(c, tx, commandId, type, target, expectedVersion, version, result);
            if (playback != null) {
                playback.events(tx, version, new ChatMusicPlaybackService.Change(true, playbackChange.playback()),
                        type);
            } else {
                commands.event(tx, version, "QUEUE_UPDATED", Map.of("action", type, "queue_item_id", item));
            }
            return result;
        });
    }

    private long append(Caller c, long music, long sequence) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM music_queue_items WHERE room_id=? AND status='WAITING'",
                Long.class, c.roomId()) >= capacity) {
            throw new ChatMusicException(409, "QUEUE_FULL");
        }
        // The catalog registration tool locks this same music row before replacing a verified source.
        if (jdbc.queryForList("SELECT id FROM music WHERE id=? FOR SHARE", music).isEmpty()) {
            throw new ChatMusicException(409, "MUSIC_NOT_REQUESTABLE");
        }
        long region = jdbc.queryForObject("SELECT region_id FROM chat_rooms WHERE id=?", Long.class, c.roomId());
        if (catalog.findSources(c.userId(), region, music).isEmpty()) {
            throw new ChatMusicException(409, "MUSIC_NOT_REQUESTABLE");
        }
        jdbc.update("INSERT INTO music_queue_items(room_id,music_id,added_by_user_id,sequence_no) VALUES (?,?,?,?)",
                c.roomId(), music, c.userId(), sequence);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Map<String, Object> paginated(List<Map<String, Object>> items, int size, String scope, long version,
            long now, String key) {
        boolean more = items.size() > size;
        var visible = more ? items.subList(0, size) : items;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", visible);
        result.put("next_cursor", more
                ? cursors.encode(scope,
                        ((Number) visible.getLast().get(key)).longValue(), version, now)
                : null);
        return result;
    }

    private String scope(Caller c, String environment, String kind, int size) {
        String content = environment + ":" + c.userId() + ":" + c.sid() + ":" + c.roomId() + ":" + c.membershipId()
                + ":" + c.viewId() + ":" + kind + ":" + size;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private void checkSize(int size) {
        if (size < 1 || size > 20) {
            throw new ChatMusicException(400, "INVALID_PAGE_SIZE");
        }
    }

    private static void positive(long value) {
        if (value < 1 || value > MAX_NUMBER) {
            throw new ChatMusicException(400, "INVALID_ID");
        }
    }

    public static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new ChatMusicException(400, "INVALID_UUID");
        }
    }
}
