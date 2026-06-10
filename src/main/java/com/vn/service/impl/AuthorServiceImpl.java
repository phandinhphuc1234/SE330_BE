package com.vn.service.impl;

import com.vn.dto.catalog.request.CreateAuthorRequest;
import com.vn.dto.catalog.request.UpdateAuthorRequest;
import com.vn.dto.catalog.response.AuthorResponse;
import com.vn.entity.Author;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.AuthorMapper;
import com.vn.repository.AuthorRepository;
import com.vn.service.AuthorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorServiceImpl implements AuthorService {

    private static final int DEFAULT_PAGE_SIZE = 6;
    private static final int MAX_PAGE_SIZE = 50;

    private final AuthorRepository authorRepository;
    private final AuthorMapper authorMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<AuthorResponse> getAuthors(String q, String name, int page, int size) {
        String searchName = resolveSearchName(q, name);
        Pageable pageable = buildPageable(page, size);
        Page<Author> authors = searchName == null
                ? authorRepository.findAll(pageable)
                : authorRepository.findByNameContainingIgnoreCase(searchName, pageable);

        return authors.map(authorMapper::toAuthorResponse);
    }

    @Override
    @Transactional
    public AuthorResponse createAuthor(CreateAuthorRequest request) {
        String name = normalizeRequired(request.name());
        if (authorRepository.existsByNameIgnoreCase(name)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }

        Author author = Author.builder()
                .name(name)
                .bio(normalizeOptional(request.bio(), null))
                .build();

        Author savedAuthor = authorRepository.save(author);

        log.info("eventType={} result={} entityType=AUTHOR entityId={}",
                LogEvent.CREATE_AUTHOR, LogResult.SUCCESS, savedAuthor.getId());

        return authorMapper.toAuthorResponse(savedAuthor);
    }
    // Update tác giả trong database
    @Override
    @Transactional
    public AuthorResponse updateAuthor(Long authorId, UpdateAuthorRequest request) {
        Author author = getAuthor(authorId);

        if (request.name() != null) {
            String name = normalizeRequired(request.name());
            authorRepository.findByNameIgnoreCase(name)
                    .filter(existing -> !existing.getId().equals(authorId))
                    .ifPresent(existing -> {
                        throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
                    });
            author.setName(name);
        }

        if (request.bio() != null) {
            author.setBio(normalizeOptional(request.bio(), null));
        }

        Author savedAuthor = authorRepository.save(author);

        log.info("eventType={} result={} entityType=AUTHOR entityId={}",
                LogEvent.UPDATE_AUTHOR, LogResult.SUCCESS, savedAuthor.getId());

        return authorMapper.toAuthorResponse(savedAuthor);
    }
    // Tìm tác giả theo ID, nếu không có thì báo lỗi
    private Author getAuthor(Long authorId) {
        return authorRepository.findById(authorId)
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

    // Chức năng: chọn keyword search cho API danh sách tác giả; name ưu tiên hơn q.
    private String resolveSearchName(String q, String name) {
        String normalizedName = normalizeSearch(name);
        return normalizedName != null ? normalizedName : normalizeSearch(q);
    }

    private Pageable buildPageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "name"));
    }

    // Chuẩn hóa keyword search: trim, lowercase, rỗng thì bỏ qua filter.
    private String normalizeSearch(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}

