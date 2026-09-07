package com.vn.service.rag;

import com.vn.config.RagServiceProperties;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestRagIngestionClient implements RagIngestionClient {

    private static final String INTERNAL_API_KEY_HEADER = "X-RAG-API-Key";

    private final RestClient restClient;
    private final RagServiceProperties properties;

    public RestRagIngestionClient(RestClient.Builder builder, RagServiceProperties properties) {
        this.restClient = builder.baseUrl(properties.serviceUrl()).build();
        this.properties = properties;
    }

    @Override
    public IngestionResponse ingestLibraryEbook(IngestionRequest request) {
        if (!StringUtils.hasText(properties.internalApiKey())) {
            throw new AppException(ErrorCode.RAG_SERVICE_CONFIG_MISSING);
        }
        try {
            return restClient.post()
                    .uri("/internal/ingestions")
                    .header(INTERNAL_API_KEY_HEADER, properties.internalApiKey())
                    .body(request)
                    .retrieve()
                    .body(IngestionResponse.class);
        } catch (HttpStatusCodeException exception) {
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        } catch (RestClientException exception) {
            throw new AppException(ErrorCode.RAG_SERVICE_ERROR);
        }
    }
}
