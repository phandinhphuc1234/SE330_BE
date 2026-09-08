package com.vn.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorResponseWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter();

    @Test
    void write_shouldReturnStandardApiResponseForSecurityError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ErrorCode.UNAUTHORIZED);

        var body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
        assertThat(body.path("message").asText()).isEqualTo(ErrorCode.UNAUTHORIZED.getMessage());
        assertThat(body.path("traceId").asText()).isNotBlank();
    }
}
