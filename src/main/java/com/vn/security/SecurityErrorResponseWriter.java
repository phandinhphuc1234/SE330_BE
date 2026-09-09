package com.vn.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.dto.common.ApiResponse;
import com.vn.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.errorWithTrace(errorCode.getCode(), errorCode.getMessage(), getTraceId())
        );
    }

    private String getTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}
