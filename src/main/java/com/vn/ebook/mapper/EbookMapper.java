package com.vn.ebook.mapper;

import com.vn.ebook.dto.response.EbookLoanResponse;
import com.vn.book.entity.Book;
import com.vn.ebook.entity.EbookLoan;
import com.vn.book.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EbookMapper {

    private final BookRepository bookRepository;

    public EbookLoanResponse toEbookLoanResponse(EbookLoan loan) {
        // Lấy title từ bookId lưu trong loan.
        String bookTitle = bookRepository.findById(loan.getBookId())
                .map(Book::getTitle)
                .orElse("N/A");

        return new EbookLoanResponse(
                loan.getId(),
                loan.getMemberId(),
                loan.getBookId(),
                bookTitle,
                loan.getBookEbookId(),
                loan.getPaymentId(),
                loan.getStatus().name(),
                loan.getBorrowedAt(),
                loan.getExpiredAt(),
                loan.getReturnedAt()
        );
    }
}
