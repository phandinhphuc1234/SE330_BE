package com.vn.entity;

import com.vn.enums.AutoRenewalAttemptResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "auto_renewal_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoRenewalAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "borrow_record_id", nullable = false)
    private BorrowRecord borrowRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_copy_id", nullable = false)
    private BookCopy bookCopy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_execution_log_id")
    private JobExecutionLog jobExecutionLog;

    @Column(nullable = false)
    private Instant attemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AutoRenewalAttemptResult result;

    @Column(length = 100)
    private String reasonCode;

    @Column(columnDefinition = "TEXT")
    private String reasonMessage;

    private Instant oldDueDate;

    private Instant newDueDate;

    private Integer renewCountBefore;

    private Integer renewCountAfter;
}
