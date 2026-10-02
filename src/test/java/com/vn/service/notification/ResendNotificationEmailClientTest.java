package com.vn.service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vn.config.NotificationDeliveryProperties;
import com.vn.service.impl.notification.delivery.ResendNotificationEmailClient;
import com.vn.service.notification.delivery.EmailProviderResult;
import com.vn.service.notification.delivery.NotificationDeliveryException;
import com.vn.service.notification.delivery.RenderedNotificationEmail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResendNotificationEmailClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendShouldUseBearerAuthenticationAndStableIdempotencyKey() throws Exception {
        server.createContext("/emails", exchange -> respond(exchange, 200, "{\"id\":\"email-123\"}", null));
        server.start();
        ResendNotificationEmailClient client = client();

        EmailProviderResult result = client.send(email());

        assertThat(result.providerMessageId()).isEqualTo("email-123");
        assertThat(authorization.get()).isEqualTo("Bearer re_test_key");
        assertThat(idempotencyKey.get()).isEqualTo("notification-10-delivery-1");
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("from").asText()).isEqualTo("Library <no-reply@example.com>");
        assertThat(body.path("to").get(0).asText()).isEqualTo("reader@example.com");
        assertThat(body.path("subject").asText()).isEqualTo("Tài khoản đã bị khóa");
        assertThat(body.path("html").asText()).isEqualTo("<p>Đã khóa</p>");
        assertThat(body.path("tags").get(0).path("name").asText()).isEqualTo("queue_id");
        assertThat(body.path("tags").get(0).path("value").asText()).isEqualTo("10");
        assertThat(body.path("tags").get(1).path("name").asText()).isEqualTo("delivery_attempt");
        assertThat(body.path("tags").get(1).path("value").asText()).isEqualTo("1");
    }

    @Test
    void rateLimitResponseShouldBeRetryableAndRespectRetryAfter() {
        server.createContext(
                "/emails",
                exchange -> respond(
                        exchange,
                        429,
                        "{\"name\":\"rate_limit_exceeded\",\"message\":\"slow down\"}",
                        "120"
                )
        );
        server.start();
        ResendNotificationEmailClient resendClient = client();
        RenderedNotificationEmail notificationEmail = email();

        assertThatThrownBy(() -> resendClient.send(notificationEmail))
                .isInstanceOf(NotificationDeliveryException.class)
                .satisfies(exception -> {
                    NotificationDeliveryException deliveryException = (NotificationDeliveryException) exception;
                    assertThat(deliveryException.retryable()).isTrue();
                    assertThat(deliveryException.retryAfter()).isEqualTo(Duration.ofSeconds(120));
                    assertThat(deliveryException.errorCode())
                            .isEqualTo("RESEND_HTTP_429_RATE_LIMIT_EXCEEDED");
                });
    }

    @Test
    void invalidRequestShouldBePermanent() {
        server.createContext(
                "/emails",
                exchange -> respond(
                        exchange,
                        422,
                        "{\"name\":\"validation_error\",\"message\":\"invalid recipient\"}",
                        null
                )
        );
        server.start();
        ResendNotificationEmailClient resendClient = client();
        RenderedNotificationEmail notificationEmail = email();

        assertThatThrownBy(() -> resendClient.send(notificationEmail))
                .isInstanceOf(NotificationDeliveryException.class)
                .satisfies(exception -> {
                    NotificationDeliveryException deliveryException = (NotificationDeliveryException) exception;
                    assertThat(deliveryException.retryable()).isFalse();
                    assertThat(deliveryException.errorCode())
                            .isEqualTo("RESEND_HTTP_422_VALIDATION_ERROR");
                });
    }

    private ResendNotificationEmailClient client() {
        return new ResendNotificationEmailClient(
                RestClient.builder(),
                properties()
        );
    }

    private RenderedNotificationEmail email() {
        return new RenderedNotificationEmail(
                10L,
                "ACCOUNT_BANNED:501:EMAIL",
                "notification-10-delivery-1",
                1,
                "reader@example.com",
                "Tài khoản đã bị khóa",
                "<p>Đã khóa</p>"
        );
    }

    private NotificationDeliveryProperties properties() {
        return new NotificationDeliveryProperties(
                true,
                10,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                baseUrl,
                "re_test_key",
                "Library <no-reply@example.com>"
        );
    }

    private void respond(HttpExchange exchange, int status, String body, String retryAfter) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
