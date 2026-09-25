package com.muse.meomuneum.chat.room.exception;

import org.springframework.http.HttpStatus;

import com.muse.meomuneum.global.exception.ErrorCode;

public enum ChatRoomErrorCode implements ErrorCode {

    CHAT_ROOM_NOT_FOUND("CHAT_ROOM_404_NOT_FOUND", HttpStatus.NOT_FOUND, "chat room not found"), USER_NOT_FOUND(
            "CHAT_ROOM_403_USER_NOT_FOUND", HttpStatus.FORBIDDEN, "chat use restricted"), LOCATION_REGION_MISMATCH(
                    "CHAT_ROOM_400_LOCATION_REGION_MISMATCH", HttpStatus.BAD_REQUEST,
                    "location region does not match chat room region"), CHAT_ROOM_CAPACITY_EXCEEDED(
                            "CHAT_ROOM_409_CAPACITY_EXCEEDED", HttpStatus.CONFLICT, "chat room capacity exceeded");

    private final String code;
    private final HttpStatus status;
    private final String message;

    ChatRoomErrorCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
