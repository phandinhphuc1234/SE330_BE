package com.vn.service.impl.importer.job;

import com.vn.service.impl.importer.csv.BookImportProcessor;
import com.vn.service.impl.importer.model.BookImportProgress;
import com.vn.service.impl.importer.model.BookImportProgressListener;

import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookImportAsyncProcessor {

    private final BookImportProcessor bookImportProcessor;
    private final BookImportJobTracker jobTracker;

    // Chức năng: chạy import trong background thread, cập nhật job status và luôn xóa file tạm sau xử lý.
    @Async("csvImportExecutor")
    public void processAsync(UUID jobId, Path filePath) {
        try {
            jobTracker.markProcessing(jobId);
            bookImportProcessor.importFromCsv(filePath, new BookImportProgressListener() {
                @Override
                public void onTotalRowsKnown(int totalRows) {
                    jobTracker.setTotalRows(jobId, totalRows);
                }

                @Override
                public void onProgress(BookImportProgress progress) {
                    jobTracker.appendProgress(jobId, progress);
                }
            });
            jobTracker.markCompleted(jobId);
        } catch (AppException ex) {
            jobTracker.markFailed(jobId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("CSV import job failed. jobId={}", jobId, ex);
            jobTracker.markFailed(jobId, ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
        } finally {
            deleteTempFile(filePath);
        }
    }
    // Xóa file sau khi sử lý
    private void deleteTempFile(Path filePath) {
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException ex) {
            log.warn("Could not delete temp CSV import file. path={}", filePath, ex);
        }
    }
}
