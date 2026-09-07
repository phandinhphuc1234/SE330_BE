package com.vn.mapper;

import com.vn.dto.review.response.BookReviewResponse;
import com.vn.entity.BookReview;
import org.springframework.stereotype.Component;

@Component
public class BookReviewMapper {

    public BookReviewResponse toResponse(BookReview review) {
        return new BookReviewResponse(
                review.getId(),
                review.getBook().getId(),
                review.getMember().getId(),
                review.getMember().getFullName(),
                review.getRating(),
                review.getContent(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
