package com.vn.notification.repository;

import com.vn.notification.entity.NotificationQueue;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface NotificationQueueRepository extends JpaRepository<NotificationQueue, Long>,
        JpaSpecificationExecutor<NotificationQueue> {

    Optional<NotificationQueue> findByEventKey(String eventKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select queue from NotificationQueue queue where queue.id = :id")
    Optional<NotificationQueue> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select queue from NotificationQueue queue where queue.providerMessageId = :providerMessageId")
    Optional<NotificationQueue> findByProviderMessageIdForUpdate(
            @Param("providerMessageId") String providerMessageId
    );

    @Query("select queue.status, count(queue) from NotificationQueue queue group by queue.status")
    List<Object[]> countGroupedByStatus();
}
