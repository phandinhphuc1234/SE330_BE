package com.vn.service.impl.importer.chunk;

import com.vn.service.impl.importer.batch.BookCopyBatchInserter;
import com.vn.service.impl.importer.model.BookCopyInsertRow;
import com.vn.service.impl.importer.model.BookImportCache;
import com.vn.service.impl.importer.model.BookImportChunkResult;
import com.vn.service.impl.importer.model.BookImportCsvRow;
import com.vn.service.impl.importer.model.PreparedBookCopyImport;

import com.vn.dto.catalog.response.BookImportRowErrorResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BookImportChunkProcessor {

    private final BookImportRowService bookImportRowService;
    private final BookCopyBatchInserter bookCopyBatchInserter;
    private final BookRepository bookRepository;
    private final EntityManager entityManager;

    // Chức năng: xử lý một chunk CSV trong transaction riêng để file lớn không nằm trong một transaction khổng lồ.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookImportChunkResult importChunk(List<BookImportCsvRow> rows, BookImportCache cache) {
        List<BookImportRowErrorResponse> errors = new ArrayList<>();
        List<BookCopyInsertRow> copiesToInsert = new ArrayList<>();
        Map<Long, Integer> copyDeltaByBookId = new HashMap<>();
        int createdBooks = 0;

        for (BookImportCsvRow row : rows) {
            try {
                PreparedBookCopyImport prepared = bookImportRowService.prepareRow(row, cache);
                copiesToInsert.add(prepared.copy());
                copyDeltaByBookId.merge(prepared.bookId(), 1, Integer::sum);
                if (prepared.createdBook()) {
                    createdBooks++;
                }
            } catch (AppException ex) {
                errors.add(toRowError(row, ex.getCode(), ex.getMessage()));
            } catch (DataIntegrityViolationException ex) {
                errors.add(toRowError(row, ErrorCode.DUPLICATE_RESOURCE.getCode(), ErrorCode.DUPLICATE_RESOURCE.getMessage()));
            } catch (Exception ex) {
                errors.add(toRowError(row, ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
            }
        }

        if (!copiesToInsert.isEmpty()) {
            // Flush JPA-created books/authors/categories before direct JDBC insert uses their IDs as FK.
            entityManager.flush();

            bookCopyBatchInserter.insertCopies(copiesToInsert);

            // Counters are updated after insert. Do not rely on previously loaded Book entities after this point.
            for (Map.Entry<Long, Integer> entry : copyDeltaByBookId.entrySet()) {
                bookRepository.adjustCopyCounters(entry.getKey(), entry.getValue(), entry.getValue());
            }

            // Clear managed entities so later reads do not observe stale totalCopies/availableCopies values.
            entityManager.clear();
        }

        int successRows = copiesToInsert.size();
        return new BookImportChunkResult(successRows, errors.size(), createdBooks, successRows, errors);
    }

    private BookImportRowErrorResponse toRowError(BookImportCsvRow row, String code, String message) {
        return new BookImportRowErrorResponse(
                row.rowNumber(),
                row.isbn(),
                row.barcode(),
                code,
                message
        );
    }
}
