package com.vn.entity;

import com.vn.enums.BookImportJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "book_import_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookImportJob {

    @Id
    private UUID id;

    @Column(length = 255)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookImportJobStatus status;

    private Integer totalRows;

    private Integer processedRows;

    private Integer successRows;

    private Integer failedRows;

    private Integer createdBooks;

    private Integer createdCopies;

    private String errorMessage;

    private Instant createdAt;

    private Instant startedAt;

    private Instant completedAt;

    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null) {
            status = BookImportJobStatus.PENDING;
        }
        if (totalRows == null) {
            totalRows = 0;
        }
        if (processedRows == null) {
            processedRows = 0;
        }
        if (successRows == null) {
            successRows = 0;
        }
        if (failedRows == null) {
            failedRows = 0;
        }
        if (createdBooks == null) {
            createdBooks = 0;
        }
        if (createdCopies == null) {
            createdCopies = 0;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
