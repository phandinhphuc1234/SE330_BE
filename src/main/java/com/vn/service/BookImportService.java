package com.vn.service;

import com.vn.dto.catalog.response.BookImportJobResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface BookImportService {

    BookImportJobResponse startImportBooksFromCsv(MultipartFile file);

    BookImportJobResponse getImportJob(UUID jobId);
}
