package com.vn.ebook.repository;

import com.vn.ebook.entity.EbookLoan;
import com.vn.ebook.enums.EbookLoanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EbookLoanExpiryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired EbookLoanRepository loans;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Test
    void expiryReleasesPartialUniqueIndexWithoutChangingOtherLoansOrHistory() {
        // Hibernate create-drop does not create Flyway's PostgreSQL partial index.
        jdbc.execute("""
                create unique index ux_member_active_ebook_loan
                on ebook_loans(member_id, book_ebook_id) where status = 'ACTIVE'
                """);
        Instant now = Instant.parse("2026-10-24T09:00:00Z");
        EbookLoan elapsed = loans.saveAndFlush(loan(10L, 1001L, EbookLoanStatus.ACTIVE, now.minusSeconds(1)));
        EbookLoan boundary = loans.saveAndFlush(loan(10L, 1002L, EbookLoanStatus.ACTIVE, now));
        EbookLoan future = loans.saveAndFlush(loan(10L, 1003L, EbookLoanStatus.ACTIVE, now.plusSeconds(1)));
        EbookLoan otherMember = loans.saveAndFlush(loan(11L, 1001L, EbookLoanStatus.ACTIVE, now.minusSeconds(1)));
        EbookLoan returned = loans.saveAndFlush(loan(10L, 1001L, EbookLoanStatus.RETURNED, now.minusSeconds(1)));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThat(loans.expireElapsedActiveLoans(10L, 1001L, now)).isEqualTo(1);
            // saveAndFlush must succeed against the real partial unique index.
            loans.saveAndFlush(loan(10L, 1001L, EbookLoanStatus.ACTIVE, now.plusSeconds(3600)));
            assertThat(loans.expireElapsedActiveLoans(10L, 1002L, now)).isEqualTo(1);
            assertThat(loans.expireElapsedActiveLoans(10L, 1003L, now)).isZero();
        });

        EbookLoan historical = loans.findById(elapsed.getId()).orElseThrow();
        assertThat(historical.getStatus()).isEqualTo(EbookLoanStatus.EXPIRED);
        assertThat(historical.getExpiredAt()).isEqualTo(elapsed.getExpiredAt());
        assertThat(historical.getVersion()).isEqualTo(elapsed.getVersion() + 1);
        assertThat(historical.getUpdatedAt()).isEqualTo(now);
        assertThat(loans.findById(boundary.getId()).orElseThrow().getStatus()).isEqualTo(EbookLoanStatus.EXPIRED);
        assertThat(loans.findById(future.getId()).orElseThrow().getStatus()).isEqualTo(EbookLoanStatus.ACTIVE);
        assertThat(loans.findById(otherMember.getId()).orElseThrow().getStatus()).isEqualTo(EbookLoanStatus.ACTIVE);
        assertThat(loans.findById(returned.getId()).orElseThrow().getStatus()).isEqualTo(EbookLoanStatus.RETURNED);
        assertThat(loans.count()).isEqualTo(6);
    }

    private EbookLoan loan(Long memberId, Long ebookId, EbookLoanStatus status, Instant expiresAt) {
        EbookLoan loan = new EbookLoan();
        loan.setMemberId(memberId);
        loan.setBookId(501L);
        loan.setBookEbookId(ebookId);
        loan.setStatus(status);
        loan.setBorrowedAt(expiresAt.minusSeconds(86400));
        loan.setExpiredAt(expiresAt);
        return loan;
    }
}
