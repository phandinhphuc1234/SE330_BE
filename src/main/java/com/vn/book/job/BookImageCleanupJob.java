package com.vn.book.job;

import com.vn.book.entity.BookImage;
import com.vn.book.enums.BookImageStatus;
import com.vn.shared.exception.AppException;
import com.vn.book.repository.BookImageRepository;
import com.vn.shared.storage.MediaDeleteCommand;
import com.vn.shared.storage.MediaResourceType;
import com.vn.shared.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.book-images.cleanup", name = "enabled", havingValue = "true")
public class BookImageCleanupJob {

    private final BookImageRepository bookImageRepository;
    private final MediaStorageService mediaStorageService;

    @Value("${app.book-images.cleanup.batch-size:25}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.book-images.cleanup.fixed-delay-ms:3600000}")
    public void purgeDeletePendingImages() {
        List<BookImage> pendingImages = bookImageRepository.findByStatusOrderByUpdatedAtAsc(
                BookImageStatus.DELETE_PENDING,
                PageRequest.of(0, Math.max(batchSize, 1))
        );

        for (BookImage image : pendingImages) {
            purgeOne(image);
        }
    }

    private void purgeOne(BookImage image) {
        try {
            mediaStorageService.delete(new MediaDeleteCommand(
                    image.getPublicId(),
                    MediaResourceType.IMAGE,
                    true
            ));
            bookImageRepository.updateStatusWithDeletedAt(image.getId(), BookImageStatus.PURGED);
        } catch (AppException e) {
            log.warn("Retry purge failed for bookImageId={} publicId={}",
                    image.getId(), image.getPublicId());
        }
    }
}
