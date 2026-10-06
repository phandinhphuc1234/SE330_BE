package com.vn.notification.service.impl.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.notification.config.NotificationDeliveryProperties;
import com.vn.notification.service.delivery.EmailProviderResult;
import com.vn.notification.service.delivery.NotificationDeliveryException;
import com.vn.notification.service.delivery.NotificationEmailClient;
import com.vn.notification.service.delivery.RenderedNotificationEmail;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
public class ResendNotificationEmailClient implements NotificationEmailClient {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String UNKNOWN_PROVIDER_CODE = "UNKNOWN";

    private final RestClient restClient;
    private final NotificationDeliveryProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public ResendNotificationEmailClient(
            RestClient.Builder builder,
            NotificationDeliveryProperties properties
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder
                .baseUrl(properties.resendBaseUrl())
                .requestFactory(requestFactory)
                .build();
        this.properties = properties;
    }

    @Override
    public EmailProviderResult send(RenderedNotificationEmail email) {
        if (!StringUtils.hasText(properties.resendApiKey())) {
            throw NotificationDeliveryException.permanent("RESEND_API_KEY_MISSING", null);
        }
        try {
            ResendSendResponse response = restClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.resendApiKey())
                    .header(IDEMPOTENCY_KEY_HEADER, email.providerRequestKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new ResendSendRequest(
                            properties.fromEmail(),
                            List.of(email.recipientEmail()),
                            email.subject(),
                            email.html(),
                            List.of(
                                    new ResendTag("queue_id", email.queueId().toString()),
                                    new ResendTag("delivery_attempt", Integer.toString(email.deliveryAttempt()))
                            )
                    ))
                    .retrieve()
                    .body(ResendSendResponse.class);
            if (response == null || !StringUtils.hasText(response.id())) {
                throw NotificationDeliveryException.retryable("RESEND_RESPONSE_ID_MISSING", null, null);
            }
            return new EmailProviderResult(response.id().strip());
        } catch (NotificationDeliveryException exception) {
            throw exception;
        } catch (HttpStatusCodeException exception) {
            throw mapHttpFailure(exception);
        } catch (RestClientException exception) {
            throw NotificationDeliveryException.retryable("RESEND_NETWORK_ERROR", null, exception);
        }
    }

    private NotificationDeliveryException mapHttpFailure(HttpStatusCodeException exception) {
        int status = exception.getStatusCode().value();
        String providerCode = extractProviderCode(exception.getResponseBodyAsString());
        String errorCode = "RESEND_HTTP_" + status + "_" + providerCode;
        boolean retryable = status == 408
                || status == 425
                || status == 429
                || status >= 500
                || (status == 409 && "CONCURRENT_IDEMPOTENT_REQUESTS".equals(providerCode));
        if (retryable) {
            return NotificationDeliveryException.retryable(
                    errorCode,
                    parseRetryAfter(exception.getResponseHeaders()),
                    exception
            );
        }
        return NotificationDeliveryException.permanent(errorCode, exception);
    }

    private String extractProviderCode(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return UNKNOWN_PROVIDER_CODE;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String value = root.path("name").asText("");
            if (!StringUtils.hasText(value)) {
                value = root.path("code").asText("");
            }
            if (!StringUtils.hasText(value)) {
                return UNKNOWN_PROVIDER_CODE;
            }
            return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        } catch (Exception ignored) {
            return UNKNOWN_PROVIDER_CODE;
        }
    }

    private Duration parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(retryAfter)) {
            return null;
        }
        try {
            long seconds = Long.parseLong(retryAfter.strip());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record ResendSendRequest(
            String from,
            List<String> to,
            String subject,
            String html,
            List<ResendTag> tags
    ) {
    }

    private record ResendTag(String name, String value) {
    }

    private record ResendSendResponse(String id) {
    }
}
