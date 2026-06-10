package com.vn.service.storage.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.service.storage.MediaDeleteCommand;
import com.vn.service.storage.MediaStorageService;
import com.vn.service.storage.MediaUploadCommand;
import com.vn.service.storage.MediaUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudinaryStorageService implements MediaStorageService {

    private final Cloudinary cloudinary;

    @Override
    public MediaUploadResult upload(MediaUploadCommand command) {
        ensureConfigured();
        validateUploadCommand(command);

        try {
            // Cloudinary SDK tự xử lý HTTPS request và signature; service chỉ truyền upload options.
            Map<?, ?> response = cloudinary.uploader().upload(
                    command.file().getBytes(),
                    uploadOptions(command)
            );
            return toUploadResult(response, command);
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException(ErrorCode.INVALID_MEDIA_FILE);
        } catch (Exception e) {
            log.warn(
                    "Cloudinary SDK upload failed. category={} resourceType={} publicId={}",
                    command.category(),
                    command.resourceType(),
                    command.publicId(),
                    e
            );
            throw new AppException(ErrorCode.CLOUDINARY_UPLOAD_FAILED);
        }
    }

    @Override
    public void delete(MediaDeleteCommand command) {
        ensureConfigured();
        if (command == null || !StringUtils.hasText(command.publicId())) {
            return;
        }
        if (command.resourceType() == null) {
            throw new AppException(ErrorCode.CLOUDINARY_DELETE_FAILED);
        }

        try {
            Map<?, ?> response = cloudinary.uploader().destroy(
                    command.publicId(),
                    ObjectUtils.asMap(
                            "resource_type", command.resourceType().cloudinaryValue(),
                            "invalidate", command.invalidate()
                    )
            );

            String result = response == null ? null : asString(response.get("result"));
            if (!"ok".equals(result) && !"not found".equals(result)) {
                log.warn("Cloudinary SDK delete returned unexpected result. publicId={} result={}",
                        command.publicId(), result);
                throw new AppException(ErrorCode.CLOUDINARY_DELETE_FAILED);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn(
                    "Cloudinary SDK delete failed. resourceType={} publicId={}",
                    command.resourceType(),
                    command.publicId(),
                    e
            );
            throw new AppException(ErrorCode.CLOUDINARY_DELETE_FAILED);
        }
    }

    private Map<String, Object> uploadOptions(MediaUploadCommand command) {
        // Business service quyết định publicId/category; SDK implementation chỉ chuyển thành Cloudinary options.
        Map<String, Object> options = ObjectUtils.asMap(
                "resource_type", command.resourceType().cloudinaryValue(),
                "public_id", command.publicId()
        );

        List<String> tags = toCloudinaryTags(command.tags());
        if (!tags.isEmpty()) {
            options.put("tags", tags);
        }

        if (command.context() != null && !command.context().isEmpty()) {
            options.put("context", command.context());
        }

        return options;
    }

    private void validateUploadCommand(MediaUploadCommand command) {
        // Validation ở đây chỉ là validation kỹ thuật chung; rule MIME/size nằm ở business service.
        if (command == null
                || command.file() == null
                || command.file().isEmpty()
                || command.resourceType() == null
                || !StringUtils.hasText(command.publicId())) {
            throw new AppException(ErrorCode.INVALID_MEDIA_FILE);
        }
    }

    private MediaUploadResult toUploadResult(Map<?, ?> response, MediaUploadCommand command) {
        if (response == null || !StringUtils.hasText(asString(response.get("public_id")))) {
            throw new AppException(ErrorCode.CLOUDINARY_UPLOAD_FAILED);
        }

        // Chuẩn hóa response SDK về DTO trung lập để business service không phụ thuộc Map thô.
        return new MediaUploadResult(
                asString(response.get("public_id")),
                asString(response.get("secure_url")),
                asLong(response.get("version")),
                asString(response.get("format")),
                asString(response.get("resource_type")),
                command.file().getOriginalFilename(),
                asInteger(response.get("width")),
                asInteger(response.get("height")),
                asLong(response.get("duration")),
                asLong(response.get("bytes")),
                command.file().getContentType()
        );
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(cloudinary.config.cloudName)
                || !StringUtils.hasText(cloudinary.config.apiKey)
                || !StringUtils.hasText(cloudinary.config.apiSecret)) {
            throw new AppException(ErrorCode.CLOUDINARY_CONFIG_MISSING);
        }
    }

    private List<String> toCloudinaryTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        return tags.entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getKey()) && StringUtils.hasText(entry.getValue()))
                .map(entry -> entry.getKey() + "_" + entry.getValue())
                .toList();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        return Long.parseLong(value.toString());
    }

    private Integer asInteger(Object value) {
        Long longValue = asLong(value);
        return longValue == null ? null : longValue.intValue();
    }
}
