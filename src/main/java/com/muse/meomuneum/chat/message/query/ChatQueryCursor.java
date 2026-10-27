package com.muse.meomuneum.chat.message.query;

public record ChatQueryCursor(long userId, long roomId, long membershipId, String viewId,
        String mode, long snapshot, long position, long expiresAt, String historyAsOf,
        String snapshotId, long epoch, long generation) {
    public ChatQueryCursor(long userId, long roomId, long membershipId, String viewId,
            String mode, long snapshot, long position, long expiresAt, String historyAsOf) {
        this(userId, roomId, membershipId, viewId, mode, snapshot, position, expiresAt, historyAsOf, null, 0, 0);
    }

    public ChatQueryCursor(long userId, long roomId, long membershipId, String viewId,
            String mode, long snapshot, long position, long expiresAt) {
        this(userId, roomId, membershipId, viewId, mode, snapshot, position, expiresAt, null);
    }

    public ChatQueryCursor next(long nextPosition) {
        return new ChatQueryCursor(userId, roomId, membershipId, viewId, mode, snapshot, nextPosition, expiresAt,
                historyAsOf, snapshotId, epoch, generation);
    }
}
