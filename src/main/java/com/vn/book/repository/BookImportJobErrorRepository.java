package com.vn.book.repository;

import com.vn.book.entity.BookImportJobError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookImportJobErrorRepository extends JpaRepository<BookImportJobError, Long> {

    List<BookImportJobError> findByJobIdOrderByRowNumberAsc(UUID jobId);
}
