package com.vn;

import com.vn.loan.repository.BorrowRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuanLyThuVienApplicationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BorrowRecordRepository borrowRecordRepository;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Test
    void contextLoads() {
    }

    @Test
    void statisticsQueries_shouldAcceptConfiguredBusinessTimezone() {
        Instant from = Instant.parse("2026-05-31T17:00:00Z");
        Instant to = Instant.parse("2026-06-03T17:00:00Z");

        assertThat(borrowRecordRepository.countBorrowedPerDay(
                from, to, null, null, null, "Asia/Ho_Chi_Minh")).isEmpty();
        assertThat(borrowRecordRepository.countReturnedPerDay(
                from, to, null, null, null, "Asia/Ho_Chi_Minh")).isEmpty();
    }

    @Test
    void protectedEndpoint_shouldReturnStandardUnauthorizedResponse() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/members/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getBody())
                .contains("\"success\":false")
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"traceId\"");
    }

}

