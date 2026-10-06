package com.vn.book.service;

import com.vn.book.dto.response.BookCoverManagementResponse;
import org.springframework.web.multipart.MultipartFile;

public interface BookImageService {

    BookCoverManagementResponse addCover(Long bookId, MultipartFile file);

    BookCoverManagementResponse updateCover(Long bookId, MultipartFile file);
}
