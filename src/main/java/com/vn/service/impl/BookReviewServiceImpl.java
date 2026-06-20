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
import com.vn.service.BookReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    private final BookReviewRepository bookReviewRepository;
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final BookReviewMapper bookReviewMapper;

    @Override
    public Page<BookReviewResponse> getBookReviews(Long bookId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<BookReview> reviews = bookReviewRepository.findByBookIdOrderByCreatedAtDesc(bookId, pageable);
        return reviews.map(bookReviewMapper::toResponse);
    }

    @Override
    public BookReviewStatsResponse getBookReviewStats(Long bookId) {
        Double averageRating = bookReviewRepository.findAverageRatingByBookId(bookId);
        long totalReviews = bookReviewRepository.countByBookId(bookId);
        List<Object[]> distribution = bookReviewRepository.findRatingDistributionByBookId(bookId);

        Map<Integer, Long> ratingDistribution = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            ratingDistribution.put(i, 0L);
        }
        for (Object[] row : distribution) {
            Integer rating = (Integer) row[0];
            Long count = (Long) row[1];
            ratingDistribution.put(rating, count);
        }

        return new BookReviewStatsResponse(bookId, averageRating, totalReviews, ratingDistribution);
    }

    @Override
    @Transactional
    public BookReviewResponse createReview(Long bookId, Long memberId, CreateReviewRequest request) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(bookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        if (bookReviewRepository.findByBookIdAndMemberId(bookId, memberId).isPresent()) {
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

        BookReview savedReview = bookReviewRepository.save(review);
        return bookReviewMapper.toResponse(savedReview);
    }

    @Override
    @Transactional
    public BookReviewResponse updateReview(Long bookId, Long memberId, UpdateReviewRequest request) {
        BookReview review = bookReviewRepository.findByBookIdAndMemberId(bookId, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));

        review.setRating(request.rating());
        review.setContent(request.content());

        BookReview savedReview = bookReviewRepository.save(review);
        return bookReviewMapper.toResponse(savedReview);
    }

    @Override
    @Transactional
    public void deleteReview(Long bookId, Long memberId) {
        BookReview review = bookReviewRepository.findByBookIdAndMemberId(bookId, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));

        bookReviewRepository.delete(review);
    }

    @Override
    public Optional<BookReviewResponse> getMyReview(Long bookId, Long memberId) {
        return bookReviewRepository.findByBookIdAndMemberId(bookId, memberId)
                .map(bookReviewMapper::toResponse);
    }
}
