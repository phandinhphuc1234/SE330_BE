package com.vn.service.image;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.enums.ImageProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class BookImageUrlResolverFactory {

    private final Map<ImageProvider, BookImageUrlResolver> resolverMap;

    public BookImageUrlResolverFactory(List<BookImageUrlResolver> resolvers) {
        // Gom resolver theo provider để BookImageMapper không phụ thuộc trực tiếp Cloudinary.
        this.resolverMap = resolvers.stream()
                .collect(Collectors.toMap(
                        BookImageUrlResolver::supports,
                        resolver -> resolver,
                        (existing, replacement) -> existing,
                        () -> new EnumMap<>(ImageProvider.class)
                ));
    }

    public BookCoverImageResponse resolve(BookImage image) {
        if (image == null) {
            return null;
        }

        // Thiếu resolver là lỗi cấu hình backend, không phải lỗi nghiệp vụ của request.
        BookImageUrlResolver resolver = resolverMap.get(image.getProvider());
        if (resolver == null) {
            throw new IllegalStateException("No book image URL resolver found for provider: " + image.getProvider());
        }

        return resolver.resolve(image);
    }
}
