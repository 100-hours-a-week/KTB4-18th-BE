package com.muse.meomuneum.chat.room.exception;

import java.time.OffsetDateTime;
public class ChatRoomException extends RuntimeException {

    private final ChatRoomErrorCode errorCode;
    private final OffsetDateTime bannedUntil;

    public ChatRoomException(ChatRoomErrorCode errorCode) {
        this(errorCode, null);
    }

    public ChatRoomException(ChatRoomErrorCode errorCode, OffsetDateTime bannedUntil) {
        super(errorCode.message());
        this.bannedUntil = bannedUntil;
        this.errorCode = errorCode;
    }

    public OffsetDateTime getBannedUntil() {
        return bannedUntil;
    }

    public ChatRoomErrorCode getErrorCode() {
        return errorCode;
    }
}
