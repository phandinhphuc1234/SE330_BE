package com.vn.rag.client;

import com.vn.rag.config.RagServiceProperties;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestRagIngestionClientTest {

    private MockRestServiceServer server;
    private RestRagIngestionClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestRagIngestionClient(
                builder,
                new RagServiceProperties(true, "http://rag-service:8000", "internal-test-key",
                        Duration.ofSeconds(3), Duration.ofSeconds(30))
        );
    }

    @Test
    void getIngestionStatus_shouldCallProtectedInternalEndpointAndMapResponse() {
        server.expect(once(), requestTo("http://rag-service:8000/internal/ingestions/300"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-RAG-API-Key", "internal-test-key"))
                .andRespond(withSuccess("""
                        {
                          "documentId": "doc_ebook_200",
                          "ingestionJobId": 300,
                          "status": "INDEXED",
                          "stage": "indexed",
                          "errorCode": null,
                          "errorMessage": null
                        }
                        """, MediaType.APPLICATION_JSON));

        RagIngestionClient.IngestionStatusResponse response = client.getIngestionStatus(300L);

        assertThat(response.documentId()).isEqualTo("doc_ebook_200");
        assertThat(response.ingestionJobId()).isEqualTo(300L);
        assertThat(response.status()).isEqualTo("INDEXED");
        assertThat(response.stage()).isEqualTo("indexed");
        server.verify();
    }

    @Test
    void getIngestionStatus_shouldRejectMissingInternalConfigurationBeforeCallingRag() {
        RestRagIngestionClient disabledClient = new RestRagIngestionClient(
                RestClient.builder(),
                new RagServiceProperties(false, "http://rag-service:8000", "",
                        Duration.ofSeconds(3), Duration.ofSeconds(30))
        );

        assertThatThrownBy(() -> disabledClient.getIngestionStatus(300L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.RAG_SERVICE_CONFIG_MISSING.getCode())
                );
    }
}
