package com.vn.exception;

import com.vn.dto.common.ApiResponse;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ================= BUSINESS EXCEPTION =================
    // Các exception liên quan tới business logic
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex) {
        //  Lấy traceId
        String traceId = getTraceId();
        log.warn("eventType={} result={} errorCode={} reason={}",
                LogEvent.BUSINESS_EXCEPTION, LogResult.FAILED, ex.getCode(), ex.getMessage());
        //
        ApiResponse<Void> response = ApiResponse.errorWithDataAndTrace(
                ex.getCode(),
                ex.getMessage(),
                null,
                traceId
        );

        return ResponseEntity
                .status(ex.getStatus())
                .body(response);
    }

    // ================= VALIDATION EXCEPTION (@Valid) =================
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex) {

        String traceId = getTraceId();

        Map<String, String> errors = new LinkedHashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );

        log.warn("eventType={} result={} errorCode={} reason=FIELD_VALIDATION_FAILED errorCount={}",
                LogEvent.VALIDATION_FAILED, LogResult.FAILED, ErrorCode.VALIDATION_ERROR.getCode(), errors.size());

        ApiResponse<Map<String, String>> response = ApiResponse.errorWithDataAndTrace(
                ErrorCode.VALIDATION_ERROR.getCode(),
                ErrorCode.VALIDATION_ERROR.getMessage(),
                errors,
                traceId
        );

        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(response);
    }

    // ================= ILLEGAL ARGUMENT =================
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        String traceId = getTraceId();
        log.warn("eventType={} result={} errorCode={} reason={}",
                LogEvent.ILLEGAL_ARGUMENT, LogResult.FAILED, ErrorCode.ILLEGAL_ARGUMENT.getCode(), ex.getClass().getSimpleName());
        return ResponseEntity
                .status(ErrorCode.ILLEGAL_ARGUMENT.getStatus())
                .body(ApiResponse.errorWithTrace(ErrorCode.ILLEGAL_ARGUMENT.getCode(), ex.getMessage(), traceId));
    }
    //
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        String traceId = getTraceId();
        log.warn("eventType={} result={} errorCode={} reason={}",
                LogEvent.ACCESS_DENIED, LogResult.FAILED, ErrorCode.ACCESS_DENIED.getCode(), ex.getMessage());
        return ResponseEntity
                .status(ErrorCode.ACCESS_DENIED.getStatus())
                .body(ApiResponse.errorWithTrace(ErrorCode.ACCESS_DENIED.getCode(), ErrorCode.ACCESS_DENIED.getMessage(), traceId));
    }
    //
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        String traceId = getTraceId();
        log.warn("eventType={} result={} errorCode={} reason={}",
                LogEvent.AUTHENTICATION_FAILED, LogResult.FAILED, ErrorCode.UNAUTHORIZED.getCode(), ex.getMessage());
        return ResponseEntity
                .status(ErrorCode.UNAUTHORIZED.getStatus())
                .body(ApiResponse.errorWithTrace(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage(), traceId));
    }
    //
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        String traceId = getTraceId();
        log.warn("eventType={} result={} errorCode={} reason={}",
                LogEvent.MALFORMED_JSON, LogResult.FAILED, ErrorCode.MALFORMED_JSON.getCode(), ex.getClass().getSimpleName());
        return ResponseEntity
                .status(ErrorCode.MALFORMED_JSON.getStatus())
                .body(ApiResponse.errorWithTrace(ErrorCode.MALFORMED_JSON.getCode(), ErrorCode.MALFORMED_JSON.getMessage(), traceId));
    }
    //
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        String traceId = getTraceId();
        log.warn("eventType={} result={} errorCode={} reason={} parameter={}",
                LogEvent.MISSING_REQUEST_PARAMETER, LogResult.FAILED,
                ErrorCode.MISSING_REQUEST_PARAMETER.getCode(), ErrorCode.MISSING_REQUEST_PARAMETER.getMessage(), ex.getParameterName());
        return ResponseEntity
                .status(ErrorCode.MISSING_REQUEST_PARAMETER.getStatus())
                .body(ApiResponse.errorWithTrace(
                        ErrorCode.MISSING_REQUEST_PARAMETER.getCode(),
                        "Thiếu tham số bắt buộc: " + ex.getParameterName(),
                        traceId
                ));
    }
    //
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String traceId = getTraceId();
        log.warn("eventType={} result={} errorCode={} reason={} method={}",
                LogEvent.METHOD_NOT_ALLOWED, LogResult.FAILED,
                ErrorCode.METHOD_NOT_ALLOWED.getCode(), ErrorCode.METHOD_NOT_ALLOWED.getMessage(), ex.getMethod());
        return ResponseEntity
                .status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.errorWithTrace(ErrorCode.METHOD_NOT_ALLOWED.getCode(), ErrorCode.METHOD_NOT_ALLOWED.getMessage(), traceId));
    }
    // Xử lý request gọi tới route không tồn tại hoặc static resource không tìm thấy.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        String traceId = getTraceId();
        log.warn("eventType={} result={} errorCode={} reason={} path={}",
                LogEvent.RESOURCE_NOT_FOUND, LogResult.FAILED,
                ErrorCode.RESOURCE_NOT_FOUND.getCode(), ErrorCode.RESOURCE_NOT_FOUND.getMessage(), ex.getResourcePath());
        return ResponseEntity
                .status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ApiResponse.errorWithTrace(
                        ErrorCode.RESOURCE_NOT_FOUND.getCode(),
                        ErrorCode.RESOURCE_NOT_FOUND.getMessage(),
                        traceId
                ));
    }
    //
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolation(ConstraintViolationException ex) {
        String traceId = getTraceId();
        Map<String, String> errors = new LinkedHashMap<>();

        ex.getConstraintViolations().forEach(violation ->
                errors.put(violation.getPropertyPath().toString(), violation.getMessage())
        );

        log.warn("eventType={} result={} errorCode={} reason={} errorCount={}",
                LogEvent.CONSTRAINT_VIOLATION, LogResult.FAILED,
                ErrorCode.CONSTRAINT_VIOLATION.getCode(), ErrorCode.CONSTRAINT_VIOLATION.getMessage(), errors.size());

        return ResponseEntity
                .status(ErrorCode.CONSTRAINT_VIOLATION.getStatus())
                .body(ApiResponse.errorWithDataAndTrace(
                        ErrorCode.CONSTRAINT_VIOLATION.getCode(),
                        ErrorCode.CONSTRAINT_VIOLATION.getMessage(),
                        errors,
                        traceId
                ));
    }
    // Exception thông báo tính toàn vẹn dữ liệu bị xâm hại
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String traceId = getTraceId();
        log.error("eventType={} result={} errorCode={} reason={}",
                LogEvent.DATA_INTEGRITY_VIOLATION, LogResult.FAILED,
                ErrorCode.DATA_INTEGRITY_VIOLATION.getCode(), ErrorCode.DATA_INTEGRITY_VIOLATION.getMessage(), ex);
        return ResponseEntity
                .status(ErrorCode.DATA_INTEGRITY_VIOLATION.getStatus())
                .body(ApiResponse.errorWithTrace(
                        ErrorCode.DATA_INTEGRITY_VIOLATION.getCode(),
                        ErrorCode.DATA_INTEGRITY_VIOLATION.getMessage(),
                        traceId
                ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        String traceId = getTraceId();
        ErrorCode errorCode = isEbookUpload(request) ? ErrorCode.INVALID_EBOOK_FILE : ErrorCode.INVALID_MEDIA_FILE;
        log.warn("eventType={} result={} errorCode={} reason={} path={}",
                LogEvent.VALIDATION_FAILED, LogResult.FAILED,
                errorCode.getCode(), ex.getClass().getSimpleName(), request.getRequestURI());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.errorWithTrace(
                        errorCode.getCode(),
                        errorCode.getMessage(),
                        traceId
                ));
    }

    // ================= GENERIC EXCEPTION (FALLBACK) =================
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {

        String traceId = getTraceId();
        log.error("eventType={} result={} errorCode={} reason={}",
                LogEvent.UNHANDLED_EXCEPTION, LogResult.FAILED,
                ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ex.getClass().getSimpleName(), ex);

        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.errorWithTrace(
                        ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                        ErrorCode.INTERNAL_SERVER_ERROR.getMessage(),
                        traceId
                ));
    }

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }

    private boolean isEbookUpload(HttpServletRequest request) {
        return request != null
                && request.getRequestURI() != null
                && request.getRequestURI().matches("^/api/books/\\d+/ebooks$");
    }
}

