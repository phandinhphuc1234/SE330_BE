package com.vn.service.impl;

import com.vn.dto.catalog.request.CreateAuthorRequest;
import com.vn.dto.catalog.request.UpdateAuthorRequest;
import com.vn.dto.catalog.response.AuthorResponse;
import com.vn.entity.Author;
import com.vn.enums.ImageProvider;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.AuthorMapper;
import com.vn.repository.AuthorRepository;
import com.vn.service.AuthorService;
import com.vn.service.storage.MediaCategory;
import com.vn.service.storage.MediaDeleteCommand;
import com.vn.service.storage.MediaResourceType;
import com.vn.service.storage.MediaStorageService;
import com.vn.service.storage.MediaUploadCommand;
import com.vn.service.storage.MediaUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorServiceImpl implements AuthorService {

    private static final int DEFAULT_PAGE_SIZE = 6;
    private static final int MAX_PAGE_SIZE = 50;
    private static final long MAX_AUTHOR_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final AuthorRepository authorRepository;
    private final AuthorMapper authorMapper;
    private final MediaStorageService mediaStorageService;
    private final TransactionTemplate transactionTemplate;

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
                .imageUrl(normalizeOptional(request.imageUrl(), null))
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

        if (request.imageUrl() != null) {
            author.setImageUrl(normalizeOptional(request.imageUrl(), null));
        }

        Author savedAuthor = authorRepository.save(author);

        log.info("eventType={} result={} entityType=AUTHOR entityId={}",
                LogEvent.UPDATE_AUTHOR, LogResult.SUCCESS, savedAuthor.getId());

        return authorMapper.toAuthorResponse(savedAuthor);
    }

    @Override
    public AuthorResponse uploadAuthorImage(Long authorId, MultipartFile file) {
        return replaceAuthorImage(authorId, file);
    }

    @Override
    public AuthorResponse updateAuthorImage(Long authorId, MultipartFile file) {
        return replaceAuthorImage(authorId, file);
    }

    private AuthorResponse replaceAuthorImage(Long authorId, MultipartFile file) {
        Author author = getAuthor(authorId);
        validateAuthorImageFile(file);

        MediaUploadResult uploadResult = uploadAuthorImageToStorage(authorId, file);
        String oldPublicId = author.getImagePublicId();
        try {
            Author savedAuthor = transactionTemplate.execute(status -> saveAuthorImage(authorId, uploadResult));
            purgeOldAuthorImageIfPossible(oldPublicId);
            return authorMapper.toAuthorResponse(savedAuthor);
        } catch (RuntimeException exception) {
            cleanupNewUpload(uploadResult.publicId());
            throw exception;
        }
    }

    private Author saveAuthorImage(Long authorId, MediaUploadResult uploadResult) {
        Author author = getAuthor(authorId);
        if (!StringUtils.hasText(uploadResult.secureUrl())) {
            throw new AppException(ErrorCode.CLOUDINARY_UPLOAD_FAILED);
        }

        author.setImageProvider(ImageProvider.CLOUDINARY);
        author.setImagePublicId(uploadResult.publicId());
        author.setImageUrl(uploadResult.secureUrl());
        return authorRepository.save(author);
    }

    private MediaUploadResult uploadAuthorImageToStorage(Long authorId, MultipartFile file) {
        String publicId = "author-images/author-" + authorId + "/portrait-" + UUID.randomUUID();
        return mediaStorageService.upload(new MediaUploadCommand(
                file,
                MediaResourceType.IMAGE,
                MediaCategory.AUTHOR_IMAGE,
                publicId,
                Map.of(
                        "category", MediaCategory.AUTHOR_IMAGE.name(),
                        "authorId", String.valueOf(authorId)
                ),
                Map.of(
                        "category", MediaCategory.AUTHOR_IMAGE.name(),
                        "authorId", String.valueOf(authorId)
                )
        ));
    }

    private void validateAuthorImageFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_AUTHOR_IMAGE_BYTES) {
            throw new AppException(ErrorCode.INVALID_IMAGE_FILE);
        }

        String contentType = file.getContentType();
        if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)) {
            throw new AppException(ErrorCode.INVALID_IMAGE_FILE);
        }

        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new AppException(ErrorCode.INVALID_IMAGE_FILE);
        }
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }

        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }

        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private void purgeOldAuthorImageIfPossible(String oldPublicId) {
        if (!StringUtils.hasText(oldPublicId)) {
            return;
        }

        try {
            deleteAuthorImage(oldPublicId);
        } catch (RuntimeException exception) {
            log.warn("Could not delete old author image publicId={}", oldPublicId, exception);
        }
    }

    private void cleanupNewUpload(String publicId) {
        try {
            deleteAuthorImage(publicId);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not cleanup newly uploaded author image publicId={}", publicId, cleanupFailure);
        }
    }

    private void deleteAuthorImage(String publicId) {
        mediaStorageService.delete(new MediaDeleteCommand(
                publicId,
                MediaResourceType.IMAGE,
                true
        ));
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

