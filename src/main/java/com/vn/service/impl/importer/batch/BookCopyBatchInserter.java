package com.vn.service.impl.importer.batch;

import com.vn.service.impl.importer.model.BookCopyInsertRow;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BookCopyBatchInserter {

    private static final int BATCH_SIZE = 500;

    private static final String INSERT_SQL = """
            INSERT INTO book_copies (
                book_id, barcode, status, condition, location, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    // Chức năng: insert nhiều book_copies bằng JDBC batch để giảm overhead khi import CSV lớn.
    public void insertCopies(List<BookCopyInsertRow> rows) {
        if (rows.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, rows, BATCH_SIZE, this::bind);
    }

    private void bind(PreparedStatement ps, BookCopyInsertRow row) throws SQLException {
        ps.setLong(1, row.bookId());
        ps.setString(2, row.barcode());
        ps.setString(3, row.status());
        ps.setString(4, row.condition());
        ps.setString(5, row.location());
        ps.setTimestamp(6, Timestamp.from(row.createdAt()));
        ps.setTimestamp(7, Timestamp.from(row.updatedAt()));
    }
}
