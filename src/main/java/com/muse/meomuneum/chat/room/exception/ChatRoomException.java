package com.muse.meomuneum.chat.room.exception;

public class ChatRoomException extends RuntimeException {

    private final ChatRoomErrorCode errorCode;

    public ChatRoomException(ChatRoomErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ChatRoomErrorCode getErrorCode() {
        return errorCode;
    }
}
