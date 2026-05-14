package com.vn.mapper;

import com.vn.dto.catalog.response.BookCopyResponse;
import com.vn.entity.BookCopy;
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

