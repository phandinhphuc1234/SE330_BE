package com.vn.repository;

import com.vn.entity.BookReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookReviewRepository extends JpaRepository<BookReview, Long> {

    Page<BookReview> findByBookIdOrderByCreatedAtDesc(Long bookId, Pageable pageable);

    Optional<BookReview> findByBookIdAndMemberId(Long bookId, Long memberId);

    long countByBookId(Long bookId);

    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM BookReview r WHERE r.book.id = :bookId")
    Double findAverageRatingByBookId(@Param("bookId") Long bookId);

    @Query("SELECT r.rating as rating, COUNT(r) as cnt FROM BookReview r WHERE r.book.id = :bookId GROUP BY r.rating")
    List<Object[]> findRatingDistributionByBookId(@Param("bookId") Long bookId);

    @Query("SELECT r.book.id, AVG(r.rating), COUNT(r) FROM BookReview r WHERE r.book.id IN :bookIds GROUP BY r.book.id")
    List<Object[]> findReviewStatsByBookIds(@Param("bookIds") java.util.Collection<Long> bookIds);
}
