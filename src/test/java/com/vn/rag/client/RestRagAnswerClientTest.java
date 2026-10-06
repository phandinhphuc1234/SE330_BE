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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestRagAnswerClientTest {

    private MockRestServiceServer server;
    private RestRagAnswerClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestRagAnswerClient(
                builder,
                new RagServiceProperties(true, "http://rag-service:8000", "internal-test-key",
                        Duration.ofSeconds(3), Duration.ofSeconds(30))
        );
    }

    @Test
    void answerShouldCallProtectedEndpointWithTrustedEbookScope() {
        server.expect(once(), requestTo("http://rag-service:8000/internal/answers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-RAG-API-Key", "internal-test-key"))
                .andExpect(content().json("""
                        {
                          "question": "dependency inversion",
                          "ebookId": 1001,
                          "topK": 5,
                          "scoreThreshold": 0.7
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "answer": "DIP separates modules.",
                          "grounded": true,
                          "abstained": false,
                          "reason": null,
                          "citations": [{
                            "documentId": "doc_ebook_1001",
                            "bookId": 501,
                            "ebookId": 1001,
                            "pageStart": 42,
                            "pageEnd": 43,
                            "chunkId": "vector-1",
                            "excerpt": "Dependency inversion means...",
                            "score": 0.91
                          }],
                          "model": "test-model",
                          "promptVersion": "library-ebook-answer-v1"
                        }
                        """, MediaType.APPLICATION_JSON));

        RagAnswerClient.AnswerResponse response = client.answer(
                RagAnswerClient.AnswerRequest.forEbook(
                        "dependency inversion", 1001L, 5, new BigDecimal("0.7")
                )
        );

        assertThat(response.grounded()).isTrue();
        assertThat(response.citations().getFirst().ebookId()).isEqualTo(1001L);
        server.verify();
    }

    @Test
    void answerShouldMapProviderFailureToStableBusinessError() {
        server.expect(once(), requestTo("http://rag-service:8000/internal/answers"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.answer(
                RagAnswerClient.AnswerRequest.forEbook("query", 1001L, null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.RAG_SERVICE_ERROR.getCode()));
        server.verify();
    }
}
