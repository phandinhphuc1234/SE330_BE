package com.vn.rag.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RagClientConfigTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void builderShouldPropagateSpringTraceIdAsRagRequestId() {
        RagServiceProperties properties = new RagServiceProperties(
                true,
                "http://rag-service:8000",
                "internal-test-key",
                Duration.ofSeconds(3),
                Duration.ofSeconds(30)
        );
        RestClient.Builder builder = new RagClientConfig().ragRestClientBuilder(properties);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MDC.put("traceId", "trace-rag-smoke-123");

        server.expect(once(), requestTo("http://rag-service:8000/health"))
                .andExpect(header("X-Request-ID", "trace-rag-smoke-123"))
                .andRespond(withSuccess());

        builder.baseUrl(properties.serviceUrl()).build().get().uri("/health").retrieve().toBodilessEntity();

        server.verify();
    }
}
