package com.vn.service;

import com.vn.dto.catalog.response.BookImportResultResponse;
import org.springframework.web.multipart.MultipartFile;

public interface BookImportService {

    BookImportResultResponse importBooksFromCsv(MultipartFile file);
}

