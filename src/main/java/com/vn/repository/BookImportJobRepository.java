package com.vn.repository;

import com.vn.entity.BookImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookImportJobRepository extends JpaRepository<BookImportJob, UUID> {
}
