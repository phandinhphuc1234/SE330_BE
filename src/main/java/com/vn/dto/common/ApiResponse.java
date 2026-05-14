package com.vn.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private String code;        // business code (optional)
    private T data;
    private Object meta;        // pagination / extra info
    private LocalDateTime timestamp;
    // TraceID
    private String traceId;

    // ================= SUCCESS =================
    // Kiểu generic T để trả về dữ liệu có kiểu cụ thể
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // Overload method với meta data
    public static <T> ApiResponse<T> success(String message, T data, Object meta) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .meta(meta)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ================= ERROR =================

    public static <T> ApiResponse<T> error(String code, String message) {
        return error(code, message, null, null);
    }

    // Hàm này dùng khi lỗi nhưng bạn vẫn muốn trả thêm data chi tiết.
    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return error(code, message, data, null);
    }
    // Hàm này dùng khi lỗi hệ thống, lỗi server, lỗi security... và bạn muốn trả thêm traceId để debug/logging
    public static <T> ApiResponse<T> errorWithTrace(String code, String message, String traceId) {
        return error(code, message, null, traceId);
    }

    // Hàm này dùng khi lỗi có cả data chi tiết và traceId.
    public static <T> ApiResponse<T> errorWithDataAndTrace(String code, String message, T data, String traceId) {
        return error(code, message, data, traceId);
    }

    private static <T> ApiResponse<T> error(String code, String message, T data, String traceId) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .traceId(traceId)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return error("INTERNAL_SERVER_ERROR", message);
    }
}   

