package com.vn.service.notification;

import com.vn.entity.Member;
import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import com.vn.repository.MemberRepository;
import com.vn.repository.NotificationQueueClaimRepository;
import com.vn.repository.NotificationQueueRepository;
import com.vn.repository.NotificationRepository;
import com.vn.service.NotificationQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationQueueServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private NotificationQueueService notificationQueueService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationQueueRepository notificationQueueRepository;

    @Autowired
    private NotificationQueueClaimRepository notificationQueueClaimRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long memberId;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        notificationQueueRepository.deleteAll();
        notificationRepository.deleteAll();
        memberRepository.deleteAll();
        memberId = memberRepository.saveAndFlush(Member.builder()
                .fullName("Queue Integration Reader")
                .email("queue-reader@example.com")
                .password("hashed-password")
                .role(MemberRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .maxBorrowLimit(5)
                .build()).getId();
    }

    @Test
    void enqueueEmailShouldBeIdempotentInsideTheBusinessTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        AtomicReference<NotificationEnqueueResult> first = new AtomicReference<>();
        AtomicReference<NotificationEnqueueResult> second = new AtomicReference<>();

        transaction.executeWithoutResult(status -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            EmailNotificationCommand command = command(member, "ACCOUNT_BANNED:9001:EMAIL");
            first.set(notificationQueueService.enqueueEmail(command));
            second.set(notificationQueueService.enqueueEmail(command));
        });

        assertThat(first.get().created()).isTrue();
        assertThat(second.get().created()).isFalse();
        assertThat(second.get().notificationId()).isEqualTo(first.get().notificationId());
        assertThat(second.get().queueId()).isEqualTo(first.get().queueId());
        assertThat(notificationRepository.count()).isEqualTo(1);
        assertThat(notificationQueueRepository.count()).isEqualTo(1);
        assertThat(notificationQueueRepository.findByEventKey("ACCOUNT_BANNED:9001:EMAIL"))
                .get()
                .satisfies(queue -> {
                    assertThat(queue.getRecipientEmail()).isEqualTo("queue-reader@example.com");
                    assertThat(queue.getPayload()).containsEntry("reason", "Vi phạm quy định");
                });
    }

    @Test
    void enqueueEmailShouldRollBackTogetherWithTheBusinessTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            notificationQueueService.enqueueEmail(command(member, "ACCOUNT_BANNED:9002:EMAIL"));
            throw new RollbackMarkerException();
        })).isInstanceOf(RollbackMarkerException.class);

        assertThat(notificationRepository.count()).isZero();
        assertThat(notificationQueueRepository.count()).isZero();
    }

    @Test
    void enqueueEmailShouldRejectCallsWithoutAnExistingBusinessTransaction() {
        Member member = memberRepository.findById(memberId).orElseThrow();

        assertThatThrownBy(() -> notificationQueueService.enqueueEmail(
                command(member, "ACCOUNT_BANNED:9003:EMAIL")
        )).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void databaseClaimShouldSplitWorkAcrossWorkersAndRecoverAStaleClaim() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            notificationQueueService.enqueueEmail(command(member, "ACCOUNT_BANNED:9101:EMAIL", 9101L));
            notificationQueueService.enqueueEmail(command(member, "ACCOUNT_BANNED:9102:EMAIL", 9102L));
            notificationQueueService.enqueueEmail(command(member, "ACCOUNT_BANNED:9103:EMAIL", 9103L));
        });

        Instant now = Instant.now().plusSeconds(1);
        List<Long> workerA = transaction.execute(status -> notificationQueueClaimRepository.claimDueEmailIds(
                now,
                now.minusSeconds(120),
                "worker-a",
                2
        ));
        List<Long> workerB = transaction.execute(status -> notificationQueueClaimRepository.claimDueEmailIds(
                now,
                now.minusSeconds(120),
                "worker-b",
                2
        ));

        assertThat(workerA).hasSize(2);
        assertThat(workerB).hasSize(1);
        assertThat(new HashSet<>(workerA)).doesNotContainAnyElementsOf(workerB);

        Long staleQueueId = workerA.getFirst();
        transaction.executeWithoutResult(status -> {
            var queue = notificationQueueRepository.findByIdForUpdate(staleQueueId).orElseThrow();
            queue.setLockedAt(now.minusSeconds(300));
            notificationQueueRepository.save(queue);
        });

        List<Long> recovered = transaction.execute(status -> notificationQueueClaimRepository.claimDueEmailIds(
                now,
                now.minusSeconds(120),
                "worker-recovery",
                1
        ));

        assertThat(recovered).containsExactly(staleQueueId);
        assertThat(notificationQueueRepository.findById(staleQueueId)).get().satisfies(queue -> {
            assertThat(queue.getStatus().name()).isEqualTo("PROCESSING");
            assertThat(queue.getLockedBy()).isEqualTo("worker-recovery");
        });
    }

    private EmailNotificationCommand command(Member member, String eventKey) {
        return command(member, eventKey, 9001L);
    }

    private EmailNotificationCommand command(Member member, String eventKey, Long targetId) {
        return EmailNotificationCommand.builder()
                .member(member)
                .title("Tài khoản đã bị khóa")
                .content("Tài khoản của bạn đã bị khóa.")
                .notificationType(NotificationType.ACCOUNT_BANNED)
                .targetType(NotificationTargetType.MEMBER_STATUS_AUDIT)
                .targetId(targetId)
                .eventKey(eventKey)
                .templateCode("account-banned")
                .payload(Map.of("reason", "Vi phạm quy định"))
                .build();
    }

    private static final class RollbackMarkerException extends RuntimeException {
    }
}
