package com.vn.repository;

import com.vn.entity.Author;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    // Tìm tác giả theo tên không phân biệt hoa/thường, dùng khi tạo sách và import CSV.
    Optional<Author> findByNameIgnoreCase(String name);

    // Kiểm tra trùng tên tác giả trước khi tạo mới.
    boolean existsByNameIgnoreCase(String name);
}

