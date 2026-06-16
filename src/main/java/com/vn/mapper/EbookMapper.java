package com.vn.mapper;

import com.vn.dto.ebook.response.EbookLoanResponse;
import com.vn.entity.Book;
import com.vn.entity.EbookLoan;
import com.vn.repository.BookRepository;
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
