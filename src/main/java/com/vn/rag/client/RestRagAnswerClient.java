package com.vn.rag.client;

import com.vn.rag.config.RagServiceProperties;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestRagAnswerClient implements RagAnswerClient {

    private static final String INTERNAL_API_KEY_HEADER = "X-RAG-API-Key";

    private final RestClient restClient;
    private final RagServiceProperties properties;

    public RestRagAnswerClient(
            @Qualifier("ragRestClientBuilder") RestClient.Builder builder,
            RagServiceProperties properties
    ) {
        this.restClient = builder.baseUrl(properties.serviceUrl()).build();
        this.properties = properties;
    }

    @Override
    public AnswerResponse answer(AnswerRequest request) {
        validateConfiguration();
        try {
            return restClient.post()
                    .uri("/internal/answers")
                    .header(INTERNAL_API_KEY_HEADER, properties.internalApiKey())
                    .body(request)
                    .retrieve()
                    .body(AnswerResponse.class);
        } catch (HttpStatusCodeException exception) {
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        } catch (RestClientException exception) {
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        }
    }

    private void validateConfiguration() {
        if (!properties.enabled() || !StringUtils.hasText(properties.internalApiKey())) {
            throw new AppException(ErrorCode.RAG_SERVICE_CONFIG_MISSING);
        }
    }
}
