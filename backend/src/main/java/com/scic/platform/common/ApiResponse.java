package com.scic.platform.common;

import java.time.OffsetDateTime;

public record ApiResponse<T>(boolean success, T data, ApiError error, String requestId, OffsetDateTime timestamp) {
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> failure(String code, String message, Object details, String requestId) {
        return new ApiResponse<>(false, null, new ApiError(code, message, details), requestId, OffsetDateTime.now());
    }

    public record ApiError(String code, String message, Object details) {}
}

