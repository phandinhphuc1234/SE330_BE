package com.vn.service.impl.importer.job;

import com.vn.service.impl.importer.model.BookImportProgress;

import com.vn.dto.catalog.response.BookImportJobResponse;
import com.vn.dto.catalog.response.BookImportRowErrorResponse;
import com.vn.entity.BookImportJob;
import com.vn.entity.BookImportJobError;
import com.vn.enums.BookImportJobStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookImportJobErrorRepository;
import com.vn.repository.BookImportJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookImportJobTracker {

    private final BookImportJobRepository jobRepository;
    private final BookImportJobErrorRepository errorRepository;

    @Transactional
    public BookImportJob createJob(String originalFilename) {
        return jobRepository.save(BookImportJob.builder()
                .originalFilename(originalFilename)
                .status(BookImportJobStatus.PENDING)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID jobId) {
        BookImportJob job = getJob(jobId);
        job.setStatus(BookImportJobStatus.PROCESSING);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setTotalRows(UUID jobId, int totalRows) {
        BookImportJob job = getJob(jobId);
        job.setTotalRows(totalRows);
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendProgress(UUID jobId, BookImportProgress progress) {
        BookImportJob job = getJob(jobId);
        job.setProcessedRows(job.getProcessedRows() + progress.processedRowsDelta());
        job.setSuccessRows(job.getSuccessRows() + progress.successRowsDelta());
        job.setFailedRows(job.getFailedRows() + progress.failedRowsDelta());
        job.setCreatedBooks(job.getCreatedBooks() + progress.createdBooksDelta());
        job.setCreatedCopies(job.getCreatedCopies() + progress.createdCopiesDelta());
        jobRepository.save(job);

        if (!progress.errors().isEmpty()) {
            errorRepository.saveAll(progress.errors().stream()
                    .map(error -> toEntity(job, error))
                    .toList());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID jobId) {
        BookImportJob job = getJob(jobId);
        job.setStatus(BookImportJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String message) {
        BookImportJob job = getJob(jobId);
        job.setStatus(BookImportJobStatus.FAILED);
        job.setErrorMessage(message);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public BookImportJobResponse getJobResponse(UUID jobId) {
        BookImportJob job = getJob(jobId);
        List<BookImportRowErrorResponse> errors = errorRepository.findByJobIdOrderByRowNumberAsc(jobId).stream()
                .map(this::toResponse)
                .toList();
        return toResponse(job, errors);
    }

    private BookImportJob getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private BookImportJobError toEntity(BookImportJob job, BookImportRowErrorResponse error) {
        return BookImportJobError.builder()
                .job(job)
                .rowNumber(error.rowNumber())
                .isbn(error.isbn())
                .barcode(error.barcode())
                .code(error.code())
                .message(error.message())
                .build();
    }

    private BookImportRowErrorResponse toResponse(BookImportJobError error) {
        return new BookImportRowErrorResponse(
                error.getRowNumber(),
                error.getIsbn(),
                error.getBarcode(),
                error.getCode(),
                error.getMessage()
        );
    }

    private BookImportJobResponse toResponse(BookImportJob job, List<BookImportRowErrorResponse> errors) {
        return new BookImportJobResponse(
                job.getId(),
                job.getOriginalFilename(),
                job.getStatus().name(),
                job.getTotalRows(),
                job.getProcessedRows(),
                job.getSuccessRows(),
                job.getFailedRows(),
                job.getCreatedBooks(),
                job.getCreatedCopies(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                errors
        );
    }
}
