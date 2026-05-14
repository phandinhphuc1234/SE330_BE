package com.vn.repository;

import com.vn.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.lang.Nullable;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

    @EntityGraph(attributePaths = {"category", "authors"})
    Optional<Book> findByIdAndDeletedAtIsNull(Long id);

    @Override
    @EntityGraph(attributePaths = {"category", "authors"})
    Page<Book> findAll(@Nullable Specification<Book> spec, Pageable pageable);

    boolean existsByIsbnIgnoreCase(String isbn);

    Optional<Book> findByIsbnIgnoreCase(String isbn);

    Optional<Book> findByIsbnIgnoreCaseAndDeletedAtIsNull(String isbn);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Book book
            set book.totalCopies = book.totalCopies + :totalDelta,
                book.availableCopies = book.availableCopies + :availableDelta
            where book.id = :bookId
            """)
    int adjustCopyCounters(@Param("bookId") Long bookId,
                           @Param("totalDelta") int totalDelta,
                           @Param("availableDelta") int availableDelta);
}

