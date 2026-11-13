package com.muse.meomuneum.chat.music;

import java.util.List;

public interface ChatMusicVideoProvider {
    int MAX_CANDIDATES = 50;
    record Video(String id, String channelId, String title, String channelTitle, String thumbnailUrl,
            long durationMillis) {
    }

    List<Video> search(String title, String artist);

    /** The monotonic deadline bounds an optional saved-ID verification plus subsequent fresh search. */
    default List<Video> search(String title, String artist, long deadlineNanos) {
        return search(title, artist);
    }

    /** One batch metadata call; omitted IDs are confirmed unavailable, failures must throw. */
    List<Video> verifyAll(List<String> videoIds);

    /** Null means a successful metadata response confirmed that this exact video cannot be played. */
    Video verify(String videoId);
}
