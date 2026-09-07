package com.vn.service.impl;

import com.vn.dto.review.request.CreateReviewRequest;
import com.vn.dto.review.request.UpdateReviewRequest;
import com.vn.dto.review.response.BookReviewResponse;
import com.vn.dto.review.response.BookReviewStatsResponse;
import com.vn.entity.Book;
import com.vn.entity.BookReview;
import com.vn.entity.Member;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.BookReviewMapper;
import com.vn.repository.BookRepository;
import com.vn.repository.BookReviewRepository;
import com.vn.repository.MemberRepository;
import com.vn.repository.projection.BookReviewStatsProjection;
import com.vn.repository.projection.RatingDistributionProjection;
import com.vn.service.BookReviewService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookReviewServiceImpl implements BookReviewService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String UNIQUE_REVIEW_CONSTRAINT = "uq_book_reviews_book_member";

    private final BookReviewRepository bookReviewRepository;
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final BookReviewMapper bookReviewMapper;

    @Override
    public Page<BookReviewResponse> getBookReviews(Long bookId, int page, int size) {
        requireActiveBook(bookId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return bookReviewRepository
                .findByBookIdOrderByCreatedAtDesc(bookId, PageRequest.of(safePage, safeSize))
                .map(bookReviewMapper::toResponse);
    }

    @Override
    public BookReviewStatsResponse getBookReviewStats(Long bookId) {
        requireActiveBook(bookId);
        BookReviewStatsProjection stats = bookReviewRepository.findReviewStatsByBookIds(List.of(bookId))
                .stream()
                .findFirst()
                .orElse(null);

        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int rating = 1; rating <= 5; rating++) {
            distribution.put(rating, 0L);
        }
        for (RatingDistributionProjection row : bookReviewRepository.findRatingDistributionByBookId(bookId)) {
            distribution.put(row.getRating(), row.getTotal());
        }

        return new BookReviewStatsResponse(
                bookId,
                stats == null || stats.getAverageRating() == null ? 0.0 : stats.getAverageRating(),
                stats == null || stats.getTotalReviews() == null ? 0L : stats.getTotalReviews(),
                distribution
        );
    }

    @Override
    public Optional<BookReviewResponse> getMyReview(Long bookId, Long memberId) {
        requireActiveBook(bookId);
        return bookReviewRepository.findByBookIdAndMemberId(bookId, memberId)
                .map(bookReviewMapper::toResponse);
    }

    @Override
    @Transactional
    public BookReviewResponse createReview(Long bookId, Long memberId, CreateReviewRequest request) {
        Book book = requireActiveBook(bookId);
        if (bookReviewRepository.existsByBookIdAndMemberId(bookId, memberId)) {
            throw new AppException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        BookReview review = BookReview.builder()
                .book(book)
                .member(member)
                .rating(request.rating())
                .content(request.content())
                .build();
        try {
            return bookReviewMapper.toResponse(bookReviewRepository.saveAndFlush(review));
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, UNIQUE_REVIEW_CONSTRAINT)) {
                throw new AppException(ErrorCode.REVIEW_ALREADY_EXISTS);
            }
            throw exception;
        }
    }

    @Override
    @Transactional
    public BookReviewResponse updateReview(Long bookId, Long memberId, UpdateReviewRequest request) {
        BookReview review = findOwnedReview(bookId, memberId);
        review.setRating(request.rating());
        review.setContent(request.content());
        return bookReviewMapper.toResponse(bookReviewRepository.save(review));
    }

    @Override
    @Transactional
    public void deleteReview(Long bookId, Long memberId) {
        bookReviewRepository.delete(findOwnedReview(bookId, memberId));
    }

    private Book requireActiveBook(Long bookId) {
        return bookRepository.findByIdAndDeletedAtIsNull(bookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private BookReview findOwnedReview(Long bookId, Long memberId) {
        return bookReviewRepository.findByBookIdAndMemberId(bookId, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
