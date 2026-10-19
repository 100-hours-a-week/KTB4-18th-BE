package com.muse.meomuneum.chat.redis;

public class ChatRedisChannels {
    private final String prefix;

    public ChatRedisChannels(ChatRedisProperties properties) {
        this.prefix = properties.environment() + ":chat:";
    }

    public String roomChannel(long roomId) {
        if (roomId <= 0) {
            throw new IllegalArgumentException("roomId must be positive");
        }
        return prefix + "room:" + roomId;
    }

    public String roomPattern() {
        return prefix + "room:*";
    }

    public String controlChannel() {
        return prefix + "control:v1";
    }
}
