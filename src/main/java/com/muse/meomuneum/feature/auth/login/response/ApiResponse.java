package com.muse.meomuneum.feature.auth.login.response;

public record ApiResponse<T>(String message, T data) {

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(message, data);
    }

    public static ApiResponse<Void> failure(String message) {
        return new ApiResponse<>(message, null);
    }
}
