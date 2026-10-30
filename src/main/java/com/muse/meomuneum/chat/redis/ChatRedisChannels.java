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

    public String runtimeMarkerKey() {
        return prefix + "state:runtime";
    }

    public String roomStateKey(long roomId, long epoch, String component) {
        if (roomId <= 0 || epoch <= 0 || component == null || !component.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Chat Redis state key requires valid room, epoch and component");
        }
        return prefix + "state:{room:" + roomId + "}:e:" + epoch + ":" + component;
    }
}
