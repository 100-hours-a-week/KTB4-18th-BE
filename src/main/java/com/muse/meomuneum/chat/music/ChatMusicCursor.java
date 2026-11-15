package com.muse.meomuneum.chat.music;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Signed continuation bound to the caller, view, query and aggregate snapshot. */
public final class ChatMusicCursor {
    private final ChatMusicKeys keys;

    public ChatMusicCursor(ChatMusicKeys keys) {
        this.keys = keys;
    }

    public String encode(String scope, long last, long version, long now) {
        String payload = scope + "|" + last + "|" + version + "|" + (now + 300);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return keys.activeId() + "." + encoded + "." + keys.digest(keys.activeId(), "cursor|" + encoded);
    }

    public Page decode(String cursor, String scope, long now) {
        if (cursor == null) {
            return new Page(0, -1);
        }
        if (cursor.length() > 4096) {
            throw new ChatMusicException(400, "INVALID_CURSOR");
        }
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 3 || !keys.keyIds().contains(parts[0])
                    || !keys.matches(parts[0], parts[2], "cursor|" + parts[1])) {
                throw new IllegalArgumentException();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 4 || !scope.equals(values[0])) {
                throw new IllegalArgumentException();
            }
            if (Long.parseLong(values[3]) <= now) {
                throw new ChatMusicException(410, "CURSOR_EXPIRED");
            }
            return new Page(Long.parseLong(values[1]), Long.parseLong(values[2]));
        } catch (IllegalArgumentException invalid) {
            throw new ChatMusicException(400, "INVALID_CURSOR");
        }
    }

    public record Page(long last, long version) {
    }
}
