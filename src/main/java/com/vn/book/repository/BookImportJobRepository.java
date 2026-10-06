package com.vn.book.repository;

import com.vn.book.entity.BookImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookImportJobRepository extends JpaRepository<BookImportJob, UUID> {
}
