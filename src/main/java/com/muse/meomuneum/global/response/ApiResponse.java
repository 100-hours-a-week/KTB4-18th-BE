package com.muse.meomuneum.global.response;

public record ApiResponse<T>(String message, T data) {

    public static <T> ApiResponse<T> of(String message, T data) {
        return new ApiResponse<>(message, data);
    }

    public static ApiResponse<Void> failure(String message) {
        return new ApiResponse<>(message, null);
    }
}
