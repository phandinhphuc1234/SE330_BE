package com.vn.ebook.service.impl;

import com.vn.ebook.config.ObjectStorageProperties;
import com.vn.ebook.entity.BookEbook;
import com.vn.ebook.enums.BookEbookStatus;
import com.vn.ebook.repository.BookEbookRepository;
import com.vn.ebook.service.EbookPdfContent;
import com.vn.ebook.service.EbookRangeNotSatisfiableException;
import com.vn.ebook.service.EbookReaderSessionService;
import com.vn.ebook.service.EbookReadingAccess;
import com.vn.ebook.storage.EbookObjectStorageService;
import com.vn.shared.enums.MediaProvider;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class EbookPdfContentService {
    private static final long MAX_PDF_BYTES = 100L * 1024 * 1024;

    private final EbookReaderSessionService readerSessionService;
    private final BookEbookRepository ebookRepository;
    private final EbookObjectStorageService objectStorageService;
    private final ObjectStorageProperties storageProperties;

    @Transactional(readOnly = true)
    public EbookPdfContent read(Long memberId, Long bookId, String sessionToken, String range, boolean headOnly) {
        // Authorize before even looking up storage metadata or parsing Range.
        EbookReadingAccess access = readerSessionService.authorizeAccess(memberId, bookId, sessionToken);
        BookEbook ebook = ebookRepository.findByIdAndBookId(access.ebookId(), bookId)
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_NOT_FOUND));
        String expectedKey = "ebooks/%d/%d/original.pdf".formatted(bookId, access.ebookId());
        if (ebook.getStatus() != BookEbookStatus.ACTIVE || ebook.getProvider() != MediaProvider.SEAWEEDFS
                || !Objects.equals(ebook.getBucketName(), storageProperties.ebookBucket())
                || !Objects.equals(ebook.getObjectKey(), expectedKey)) {
            throw new AppException(ErrorCode.EBOOK_NOT_AVAILABLE);
        }

        var metadata = objectStorageService.describeRead(ebook.getBucketName(), ebook.getObjectKey());
        long total = metadata.contentLength();
        if (total <= 0 || total > MAX_PDF_BYTES) {
            throw new AppException(ErrorCode.EBOOK_CONTENT_UNAVAILABLE);
        }
        long start = 0;
        long end = total - 1;
        boolean partial = range != null;
        if (partial) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1) {
                    throw new EbookRangeNotSatisfiableException(total);
                }
                start = ranges.getFirst().getRangeStart(total);
                end = ranges.getFirst().getRangeEnd(total);
                if (start < 0 || start >= total || end < start) {
                    throw new EbookRangeNotSatisfiableException(total);
                }
            } catch (IllegalArgumentException exception) {
                throw new EbookRangeNotSatisfiableException(total);
            }
        }
        if (headOnly) {
            return new EbookPdfContent(null, total, start, end, partial);
        }
        String storageRange = partial ? "bytes=%d-%d".formatted(start, end) : null;
        var stream = objectStorageService.openRead(ebook.getBucketName(), ebook.getObjectKey(),
                storageRange, metadata.etag(), end - start + 1);
        // Known response length is set by the controller; never buffer a 100-MB PDF in the heap.
        return new EbookPdfContent(new InputStreamResource(stream), total, start, end, partial);
    }
}
