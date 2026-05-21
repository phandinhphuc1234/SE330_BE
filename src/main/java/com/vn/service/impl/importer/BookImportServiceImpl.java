package com.vn.service.impl.importer;

import com.vn.service.impl.importer.job.BookImportAsyncProcessor;
import com.vn.service.impl.importer.job.BookImportJobTracker;

import com.vn.dto.catalog.response.BookImportJobResponse;
import com.vn.entity.BookImportJob;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.service.BookImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookImportServiceImpl implements BookImportService {

    private static final long MAX_IMPORT_FILE_SIZE_BYTES = 5L * 1024 * 1024;
    private static final String TEMP_DIRECTORY_NAME = "library-book-imports";

    private final BookImportJobTracker jobTracker;
    private final BookImportAsyncProcessor asyncProcessor;

    // Chức năng: nhận upload CSV, lưu file tạm và trả jobId để frontend polling tiến độ.
    @Override
    public BookImportJobResponse startImportBooksFromCsv(MultipartFile file) {
        validateFile(file);
        Path tempFile = saveTempFile(file);
        BookImportJob job = jobTracker.createJob(file.getOriginalFilename());
        asyncProcessor.processAsync(job.getId(), tempFile);
        return jobTracker.getJobResponse(job.getId());
    }

    // Chức năng: lấy trạng thái import job để frontend hiển thị tiến độ/kết quả.
    @Override
    public BookImportJobResponse getImportJob(UUID jobId) {
        return jobTracker.getJobResponse(jobId);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        if (file.getSize() > MAX_IMPORT_FILE_SIZE_BYTES) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private Path saveTempFile(MultipartFile file) {
        try {
            Path directory = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIRECTORY_NAME));
            Path tempFile = Files.createTempFile(directory, "book-import-", ".csv");
            file.transferTo(tempFile);
            return tempFile;
        } catch (IOException ex) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }
}
