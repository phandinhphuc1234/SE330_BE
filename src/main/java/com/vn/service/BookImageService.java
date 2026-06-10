package com.vn.service;

import com.vn.dto.catalog.response.BookCoverManagementResponse;
import org.springframework.web.multipart.MultipartFile;

public interface BookImageService {

    BookCoverManagementResponse addCover(Long bookId, MultipartFile file);

    BookCoverManagementResponse updateCover(Long bookId, MultipartFile file);
}
