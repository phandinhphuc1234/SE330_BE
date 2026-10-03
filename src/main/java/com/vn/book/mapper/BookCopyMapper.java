package com.vn.book.mapper;

import com.vn.book.dto.response.BookCopyResponse;
import com.vn.book.entity.BookCopy;
import org.springframework.stereotype.Component;

@Component
public class BookCopyMapper {

    public BookCopyResponse toBookCopyResponse(BookCopy copy) {
        return new BookCopyResponse(
                copy.getId(),
                copy.getBook().getId(),
                copy.getBarcode(),
                copy.getStatus(),
                copy.getCondition(),
                copy.getLocation(),
                copy.getCreatedAt(),
                copy.getUpdatedAt()
        );
    }
}

