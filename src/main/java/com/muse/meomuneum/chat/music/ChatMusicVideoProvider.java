package com.muse.meomuneum.chat.music;

import java.util.List;

public interface ChatMusicVideoProvider {
    record Video(String id, String channelId, String title, String channelTitle, String thumbnailUrl,
            long durationMillis) {
    }

    List<Video> search(String title, String artist);

    /** Null means a successful metadata response confirmed that this exact video cannot be played. */
    Video verify(String videoId);
}
