package com.vn.rag.config;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({RagServiceProperties.class, RagIngestionSyncProperties.class})
public class RagClientConfig {

    private static final String TRACE_ID = "traceId";
    private static final String RAG_REQUEST_ID_HEADER = "X-Request-ID";

    @Bean("ragRestClientBuilder")
    RestClient.Builder ragRestClientBuilder(RagServiceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    String traceId = MDC.get(TRACE_ID);
                    if (StringUtils.hasText(traceId)) {
                        request.getHeaders().set(RAG_REQUEST_ID_HEADER, traceId);
                    }
                    return execution.execute(request, body);
                });
    }
}
