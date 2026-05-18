package com.vn.repository;

import com.vn.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    // Tìm thể loại theo tên không phân biệt hoa/thường, dùng khi tạo sách và import CSV.
    Optional<Category> findByNameIgnoreCase(String name);

    // Kiểm tra trùng tên thể loại trước khi tạo mới.
    boolean existsByNameIgnoreCase(String name);
}

