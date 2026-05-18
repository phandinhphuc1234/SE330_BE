package com.vn.repository;

import com.vn.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    // Tìm record theo đúng scope idempotency để biết request là mới, đang xử lý hay đã hoàn tất.
    Optional<IdempotencyRecord> findByActorIdAndHttpMethodAndNormalizedPathAndIdempotencyKey(
            Long actorId,
            String httpMethod,
            String normalizedPath,
            String idempotencyKey
    );

    // Tạo record PROCESSING theo kiểu atomic; nếu key đã tồn tại thì không insert để tránh race condition.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into idempotency_records (
                actor_id,
                http_method,
                normalized_path,
                idempotency_key,
                request_hash,
                status,
                created_at,
                expires_at
            )
            values (
                :actorId,
                :httpMethod,
                :normalizedPath,
                :idempotencyKey,
                :requestHash,
                'PROCESSING',
                :createdAt,
                :expiresAt
            )
            on conflict (actor_id, http_method, normalized_path, idempotency_key) do nothing
            """, nativeQuery = true)
    int insertProcessing(@Param("actorId") Long actorId,
                         @Param("httpMethod") String httpMethod,
                         @Param("normalizedPath") String normalizedPath,
                         @Param("idempotencyKey") String idempotencyKey,
                         @Param("requestHash") String requestHash,
                         @Param("createdAt") Instant createdAt,
                         @Param("expiresAt") Instant expiresAt);
}
