package com.vn.service.impl;

import com.vn.dto.catalog.request.BulkCreateBookCopiesRequest;
import com.vn.dto.catalog.request.CreateBookCopyRequest;
import com.vn.dto.catalog.request.UpdateBookCopyRequest;
import com.vn.dto.catalog.response.BookCopyResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.enums.BookCopyStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.BookCopyMapper;
import com.vn.repository.BookCopyRepository;
import com.vn.repository.BookRepository;
import com.vn.service.BookCopyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookCopyServiceImpl implements BookCopyService {

    private final BookRepository bookRepository;
    private final BookCopyRepository bookCopyRepository;
    private final BookCopyMapper bookCopyMapper;

    // Lấy danh sách bản copy của một sách, sắp xếp theo ID tăng dần
    @Override
    @Transactional(readOnly = true)
    public List<BookCopyResponse> getBookCopies(Long bookId) {
        getActiveBook(bookId);
        return bookCopyRepository.findByBookIdAndDeletedAtIsNullOrderByIdAsc(bookId).stream()
                .map(bookCopyMapper::toBookCopyResponse)
                .toList();
    }

    // Tạo mới một bản copy vật lý cho sách
    @Override
    @Transactional
    public BookCopyResponse createBookCopy(Long bookId, CreateBookCopyRequest request) {
        Book book = getActiveBook(bookId);
        BookCopy savedCopy = createBookCopyEntity(book, request);

        // Copy mới luôn AVAILABLE nên chỉ cần tăng counter, không cần COUNT lại toàn bộ book_copies.
        bookRepository.adjustCopyCounters(book.getId(), 1, 1);

        // Ghi log khi tạo bản copy thành công
        log.info("eventType={} result={} entityType=BOOK_COPY entityId={} bookId={}",
                LogEvent.CREATE_BOOK_COPY, LogResult.SUCCESS, savedCopy.getId(), bookId);

        return bookCopyMapper.toBookCopyResponse(savedCopy);
    }
    // Tạo 1 book copies bên trong hệ thống
    @Override
    @Transactional
    public List<BookCopyResponse> createBookCopies(Long bookId, BulkCreateBookCopiesRequest request) {
        Book book = getActiveBook(bookId);
        // Kiểm tra barcode c hợp lệ hay không
        validateUniqueBarcodesInRequest(request.copies());
        //
        List<BookCopy> savedCopies = request.copies().stream()
                .map(copyRequest -> createBookCopyEntity(book, copyRequest))
                .toList();
        //
        bookRepository.adjustCopyCounters(book.getId(), savedCopies.size(), savedCopies.size());

        log.info("eventType={} result={} entityType=BOOK entityId={} copyCount={}",
                LogEvent.CREATE_BOOK_COPY, LogResult.SUCCESS, bookId, savedCopies.size());

        return savedCopies.stream()
                .map(bookCopyMapper::toBookCopyResponse)
                .toList();
    }

    private BookCopy createBookCopyEntity(Book book, CreateBookCopyRequest request) {
        // Chuẩn hóa barcode và kiểm tra không được trùng
        String barcode = normalizeRequired(request.barcode());
        if (bookCopyRepository.existsByBarcodeIgnoreCase(barcode)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }

        BookCopy copy = BookCopy.builder()
                .book(book)
                .barcode(barcode)
                .status(BookCopyStatus.AVAILABLE)
                .condition(normalizeOptional(request.condition(), null))
                .location(normalizeOptional(request.location(), null))
                .build();

        return bookCopyRepository.save(copy);
    }

    // Cập nhật thông tin quản trị của bản copy. Status sẽ do nghiệp vụ mượn/trả/đặt trước xử lý.
    @Override
    @Transactional
    public BookCopyResponse updateBookCopy(Long copyId, UpdateBookCopyRequest request) {
        BookCopy copy = getBookCopy(copyId);

        // Cập nhật tình trạng vật lý của copy nếu có
        if (request.condition() != null) {
            copy.setCondition(normalizeOptional(request.condition(), null));
        }

        // Cập nhật vị trí lưu trữ của copy nếu có
        if (request.location() != null) {
            copy.setLocation(normalizeOptional(request.location(), null));
        }

        BookCopy savedCopy = bookCopyRepository.save(copy);

        // Ghi log khi cập nhật bản copy thành công
        log.info("eventType={} result={} entityType=BOOK_COPY entityId={} bookId={}",
                LogEvent.UPDATE_BOOK_COPY, LogResult.SUCCESS, savedCopy.getId(), copy.getBook().getId());

        return bookCopyMapper.toBookCopyResponse(savedCopy);
    }

    // Xóa mềm bản copy nếu không đang được mượn hoặc đặt trước.
    @Override
    @Transactional
    public void deleteBookCopy(Long copyId, Long deletedBy) {
        BookCopy copy = getBookCopy(copyId);

        // Không cho xóa copy đang được mượn hoặc đang được đặt trước
        if (copy.getStatus() == BookCopyStatus.BORROWED
                || copy.getStatus() == BookCopyStatus.RESERVED
                || copy.getStatus() == BookCopyStatus.OVERDUE
                || copy.getStatus() == BookCopyStatus.ON_HOLD_SHELF) {
            throw new AppException(ErrorCode.BOOK_HAS_ACTIVE_COPIES);
        }

        Book book = copy.getBook();
        copy.setDeletedAt(Instant.now());
        copy.setDeletedBy(deletedBy);
        bookCopyRepository.saveAndFlush(copy);

        int availableDelta = copy.getStatus() == BookCopyStatus.AVAILABLE ? -1 : 0;
        bookRepository.adjustCopyCounters(book.getId(), -1, availableDelta);

        // Ghi log khi xóa bản copy thành công
        log.info("eventType={} result={} entityType=BOOK_COPY entityId={} bookId={} deletedBy={}",
                LogEvent.DELETE_BOOK_COPY, LogResult.SUCCESS, copyId, book.getId(), deletedBy);
    }

    // Lấy sách chưa bị xóa mềm, nếu không có thì báo lỗi
    private Book getActiveBook(Long bookId) {
        return bookRepository.findByIdAndDeletedAtIsNull(bookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Tìm bản copy theo ID, nếu không có thì báo lỗi
    private BookCopy getBookCopy(Long copyId) {
        return bookCopyRepository.findByIdAndDeletedAtIsNull(copyId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    // Hàm kiểm tra xem Barcode có trùng nhau ở trong dữ liệu được parse ra từ file PDF hay không
    private void validateUniqueBarcodesInRequest(List<CreateBookCopyRequest> copies) {
        Set<String> seenBarcodes = new HashSet<>();
        for (CreateBookCopyRequest copy : copies) {
            String barcode = normalizeRequired(copy.barcode()).toLowerCase();
            if (!seenBarcodes.add(barcode)) {
                throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
            }
        }
    }

    // Chuẩn hóa field bắt buộc và không cho phép rỗng
    private String normalizeRequired(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
        return normalized;
    }

    // Chuẩn hóa field không bắt buộc, nếu rỗng thì dùng giá trị mặc định
    private String normalizeOptional(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? defaultValue : normalized;
    }
}

