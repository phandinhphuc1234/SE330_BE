package com.vn.rag.client;

import com.vn.rag.config.RagServiceProperties;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RestRagRetrievalClientTest {

    private MockRestServiceServer server;
    private RestRagRetrievalClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestRagRetrievalClient(
                builder,
                new RagServiceProperties(true, "http://rag-service:8000", "internal-test-key",
                        Duration.ofSeconds(3), Duration.ofSeconds(30))
        );
    }

    @Test
    void searchShouldCallProtectedEndpointWithExactEbookScope() {
        server.expect(once(), requestTo("http://rag-service:8000/internal/retrieval/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-RAG-API-Key", "internal-test-key"))
                .andExpect(content().json("""
                        {
                          "query": "dependency inversion",
                          "ebookId": 1001,
                          "topK": 5,
                          "scoreThreshold": 0.7
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "queryTextHash": "query-hash",
                          "queryTextPolicy": "retrieval-query",
                          "embeddingVersion": "embedding-v1",
                          "topK": 5,
                          "resultCount": 1,
                          "appliedFilters": {"ebook_id": 1001},
                          "results": [{
                            "pointId": "point-1",
                            "vectorId": "vector-1",
                            "score": 0.91,
                            "text": "Dependency inversion means...",
                            "citation": {
                              "documentId": "doc_ebook_1001",
                              "bookId": 501,
                              "ebookId": 1001,
                              "pageStart": 42,
                              "pageEnd": 43,
                              "chunkIndex": 12
                            },
                            "metadata": {}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        RagRetrievalClient.RetrievalResponse response = client.search(
                RagRetrievalClient.RetrievalRequest.forEbook(
                        "dependency inversion",
                        1001L,
                        5,
                        new BigDecimal("0.7")
                )
        );

        assertThat(response.resultCount()).isEqualTo(1);
        assertThat(response.results().getFirst().citation().ebookId()).isEqualTo(1001L);
        assertThat(response.results().getFirst().citation().pageStart()).isEqualTo(42);
        server.verify();
    }

    @Test
    void searchShouldRejectMissingInternalConfiguration() {
        RestRagRetrievalClient disabledClient = new RestRagRetrievalClient(
                RestClient.builder(),
                new RagServiceProperties(false, "http://rag-service:8000", "",
                        Duration.ofSeconds(3), Duration.ofSeconds(30))
        );

        assertThatThrownBy(() -> disabledClient.search(
                RagRetrievalClient.RetrievalRequest.forEbook("query", 1001L, null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.RAG_SERVICE_CONFIG_MISSING.getCode()));
    }

    @Test
    void searchShouldMapProviderFailureToStableBusinessError() {
        server.expect(once(), requestTo("http://rag-service:8000/internal/retrieval/search"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.search(
                RagRetrievalClient.RetrievalRequest.forEbook("query", 1001L, null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.RAG_SERVICE_ERROR.getCode()));

        server.verify();
    }
}
