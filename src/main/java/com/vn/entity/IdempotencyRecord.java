package com.vn.entity;

import com.vn.enums.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actorId;

    @Column(length = 10, nullable = false)
    private String httpMethod;

    @Column(length = 255, nullable = false)
    private String normalizedPath;

    @Column(length = 255, nullable = false)
    private String idempotencyKey;

    @Column(length = 64, nullable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private IdempotencyStatus status;

    private Integer responseCode;

    private String responseBody;

    private Instant createdAt;

    private Instant completedAt;

    private Instant expiresAt;

    @Column(length = 100)
    private String errorCode;

    private String errorMessage;
}
