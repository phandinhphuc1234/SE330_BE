package com.vn.book.mapper;

import com.vn.book.dto.response.AuthorResponse;
import com.vn.book.dto.response.BookDetailResponse;
import com.vn.book.dto.response.BookCoverImageResponse;
import com.vn.book.dto.response.BookSummaryResponse;
import com.vn.book.entity.Author;
import com.vn.book.entity.Book;
import com.vn.book.entity.BookImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BookMapper {

    private final AuthorMapper authorMapper;
    private final CategoryMapper categoryMapper;
    private final BookImageMapper bookImageMapper;

    // Ảnh chính nằm ở book_images; response chỉ expose coverImage cho frontend.
    public BookSummaryResponse toBookSummaryResponse(Book book, BookImage primaryImage) {
        return toBookSummaryResponse(book, primaryImage, 0.0, 0L);
    }

    public BookSummaryResponse toBookSummaryResponse(
            Book book,
            BookImage primaryImage,
            Double averageRating,
            Long totalReviews) {
        BookCoverImageResponse coverImage = bookImageMapper.toCoverImageResponse(primaryImage);
        return new BookSummaryResponse(
                book.getId(),
                book.getTitle(),
                book.getIsbn(),
                book.getPublishedDate(),
                book.getLanguage(),
                book.getEdition(),
                coverImage,
                book.getTotalCopies(),
                book.getAvailableCopies(),
                categoryMapper.toCategoryResponse(book.getCategory()),
                toAuthorResponses(book),
                averageRating,
                totalReviews
        );
    }

    // Frontend dùng coverImage.detailUrl cho detail page.
    public BookDetailResponse toBookDetailResponse(Book book, BookImage primaryImage) {
        return toBookDetailResponse(book, primaryImage, 0.0, 0L);
    }

    public BookDetailResponse toBookDetailResponse(
            Book book,
            BookImage primaryImage,
            Double averageRating,
            Long totalReviews) {
        BookCoverImageResponse coverImage = bookImageMapper.toCoverImageResponse(primaryImage);
        return new BookDetailResponse(
                book.getId(),
                book.getTitle(),
                book.getIsbn(),
                book.getPublishedDate(),
                book.getLanguage(),
                book.getEdition(),
                coverImage,
                book.getTotalCopies(),
                book.getAvailableCopies(),
                categoryMapper.toCategoryResponse(book.getCategory()),
                toAuthorResponses(book),
                book.getCreatedAt(),
                book.getUpdatedAt(),
                averageRating,
                totalReviews
        );
    }

    private List<AuthorResponse> toAuthorResponses(Book book) {
        return book.getAuthors().stream()
                .sorted(Comparator.comparing(Author::getName))
                .map(authorMapper::toAuthorResponse)
                .toList();
    }
}

