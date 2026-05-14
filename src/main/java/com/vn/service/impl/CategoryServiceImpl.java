package com.vn.service.impl;

import com.vn.dto.catalog.request.CreateCategoryRequest;
import com.vn.dto.catalog.request.UpdateCategoryRequest;
import com.vn.dto.catalog.response.CategoryResponse;
import com.vn.entity.Category;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.CategoryMapper;
import com.vn.repository.CategoryRepository;
import com.vn.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    // Lấy danh sách danh mục và sắp xếp theo tên tăng dần
    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> getCategories() {
        return categoryRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
                .map(categoryMapper::toCategoryResponse)
                .toList();
    }

    // Tạo mới danh mục sau khi chuẩn hóa và kiểm tra trùng tên
    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        // Normalize tên của Category
        String name = normalizeRequired(request.name());
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }
        Category category = Category.builder()
                .name(name)
                .description(normalizeOptional(request.description(), null))
                .build();

        Category savedCategory = categoryRepository.save(category);

        // Ghi log khi tạo danh mục thành công
        log.info("eventType={} result={} entityType=CATEGORY entityId={}",
                LogEvent.CREATE_CATEGORY, LogResult.SUCCESS, savedCategory.getId());

        return categoryMapper.toCategoryResponse(savedCategory);
    }

    // Cập nhật thông tin danh mục theo categoryId
    @Override
    @Transactional
    public CategoryResponse updateCategory(Long categoryId, UpdateCategoryRequest request) {
        Category category = getCategory(categoryId);

        // Nếu cập nhật tên thì chuẩn hóa và kiểm tra trùng với danh mục khác
        if (request.name() != null) {
            String name = normalizeRequired(request.name());
            categoryRepository.findByNameIgnoreCase(name)
                    .filter(existing -> !existing.getId().equals(categoryId))
                    .ifPresent(existing -> {
                        throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
                    });
            category.setName(name);
        }

        // Cập nhật mô tả nếu request có truyền lên
        if (request.description() != null) {
            category.setDescription(normalizeOptional(request.description(), null));
        }

        Category savedCategory = categoryRepository.save(category);

        // Ghi log khi cập nhật danh mục thành công
        log.info("eventType={} result={} entityType=CATEGORY entityId={}",
                LogEvent.UPDATE_CATEGORY, LogResult.SUCCESS, savedCategory.getId());

        return categoryMapper.toCategoryResponse(savedCategory);
    }

    // Tìm danh mục theo ID, nếu không có thì báo lỗi
    private Category getCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Chuẩn hóa field bắt buộc và không cho phép rỗng
    private String normalizeRequired(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
        return normalized;
    }

    // Chuẩn hóa field không bắt buộc, nếu rỗng thì dùng giá trị mặc định
    private String normalizeOptional(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? defaultValue : normalized;
    }
}
