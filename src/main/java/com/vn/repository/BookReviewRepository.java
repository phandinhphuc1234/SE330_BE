package com.vn.repository;

import com.vn.entity.BookReview;
import com.vn.repository.projection.BookReviewStatsProjection;
import com.vn.repository.projection.RatingDistributionProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookReviewRepository extends JpaRepository<BookReview, Long> {

    @EntityGraph(attributePaths = {"book", "member"})
    Page<BookReview> findByBookIdOrderByCreatedAtDesc(Long bookId, Pageable pageable);

    @EntityGraph(attributePaths = {"book", "member"})
    Optional<BookReview> findByBookIdAndMemberId(Long bookId, Long memberId);

    boolean existsByBookIdAndMemberId(Long bookId, Long memberId);

    @Query("""
            select review.book.id as bookId,
                   avg(review.rating) as averageRating,
                   count(review) as totalReviews
            from BookReview review
            where review.book.id in :bookIds
            group by review.book.id
            """)
    List<BookReviewStatsProjection> findReviewStatsByBookIds(@Param("bookIds") Collection<Long> bookIds);

    @Query("""
            select review.rating as rating, count(review) as total
            from BookReview review
            where review.book.id = :bookId
            group by review.rating
            order by review.rating
            """)
    List<RatingDistributionProjection> findRatingDistributionByBookId(@Param("bookId") Long bookId);
}
