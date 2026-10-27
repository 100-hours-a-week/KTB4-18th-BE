package com.muse.meomuneum.chat.participation;

import java.util.List;

/** Called only after the DB transaction commits; Redis never grants a seat or permission. */
public interface ChatParticipationProjection {
    void write(Snapshot snapshot);

    record Snapshot(long epoch, long roomId, long version, int occupied, int connected, List<View> views) {
    }

    record View(String id, long version, long userId, long membershipId, String state, String deadline,
            String owner, long generation) {
    }
}
