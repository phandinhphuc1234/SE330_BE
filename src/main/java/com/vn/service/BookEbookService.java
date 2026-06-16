package com.vn.service;

import com.vn.dto.ebook.request.UpdateBookEbookRequest;
import com.vn.dto.ebook.response.BookEbookManagementResponse;
import com.vn.dto.ebook.response.BookEbookPublicResponse;
import com.vn.dto.ebook.response.BookEbookUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface BookEbookService {

    // Upload file PDF chính của sách lên Cloudinary protected storage và lưu metadata vào book_ebooks.
    BookEbookUploadResponse uploadMainPdf(Long bookId, MultipartFile file);

    // Metadata an toàn cho trang chi tiết sách/member UI; không trả publicId hoặc URL PDF.
    BookEbookPublicResponse getPublicEbook(Long bookId);

    // Metadata đầy đủ cho staff/admin quản trị ebook.
    BookEbookManagementResponse getManagementEbook(Long bookId, Long bookEbookId);

    // Cập nhật policy/trạng thái ebook, không thay file PDF. Thay file dùng API upload hiện có.
    BookEbookManagementResponse updateEbook(Long bookId, Long bookEbookId, UpdateBookEbookRequest request);
}
