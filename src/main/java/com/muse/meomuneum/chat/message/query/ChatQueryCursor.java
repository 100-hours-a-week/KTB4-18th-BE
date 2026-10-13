package com.muse.meomuneum.chat.message.query;

public record ChatQueryCursor(long userId, long roomId, long membershipId, String viewId,
        String mode, long snapshot, long position, long expiresAt) {
    public ChatQueryCursor next(long nextPosition) {
        return new ChatQueryCursor(userId, roomId, membershipId, viewId, mode, snapshot, nextPosition, expiresAt);
    }
}
