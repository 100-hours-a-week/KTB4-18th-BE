package com.muse.meomuneum.chat.participation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;

import io.lettuce.core.ScriptOutputType;

public final class RedisChatParticipationProjection implements ChatParticipationProjection {
    // Separate per-view versions prevent a newer count update from hiding an older, independent view update.
    private static final String CAS = """
            local previous = redis.call('HGET', KEYS[1], 'version')
            if not previous or tonumber(previous) < tonumber(ARGV[1]) then
                redis.call('HSET', KEYS[1], 'version', ARGV[1], 'snapshot', ARGV[2])
                redis.call('EXPIRE', KEYS[1], 86400)
                return 1
            end
            return 0
            """;
    private final ChatRedisConnection connection;
    private final ChatRedisChannels channels;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisChatParticipationProjection(ChatRedisConnection connection, ChatRedisChannels channels) {
        this.connection = connection;
        this.channels = channels;
    }

    @Override
    public void write(Snapshot snapshot) {
        try {
            update(channels.roomStateKey(snapshot.roomId(), snapshot.epoch(), "presence"), snapshot.version(),
                    mapper.writeValueAsString(java.util.Map.of("occupied", snapshot.occupied(),
                            "connected", snapshot.connected(), "version", snapshot.version())));
            for (View view : snapshot.views()) {
                // UUID is a hash field/key suffix, never a client supplied Redis command or channel.
                update(channels.roomStateKey(snapshot.roomId(), snapshot.epoch(), "views") + ":" + view.id(),
                        view.version(), mapper.writeValueAsString(view));
            }
        } catch (JsonProcessingException invalid) {
            throw new ChatParticipationUnavailableException();
        }
    }

    private void update(String key, long version, String body) {
        connection.commands().sync().eval(CAS, ScriptOutputType.INTEGER, new String[]{key},
                Long.toString(version), body);
    }
}
