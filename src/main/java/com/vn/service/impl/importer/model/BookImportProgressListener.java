package com.vn.service.impl.importer.model;

// Callback đơn giản để BookImportProcessor báo tiến độ ra ngoài mà không phụ thuộc trực tiếp vào entity job.
// BookImportJobTracker là implementation thực tế thông qua anonymous listener trong async processor.
public interface BookImportProgressListener {

    // Gọi sau khi parse xong để frontend biết tổng số row cần xử lý.
    void onTotalRowsKnown(int totalRows);

    // Gọi sau mỗi nhóm lỗi hoặc sau mỗi chunk để cập nhật progress/job errors.
    void onProgress(BookImportProgress progress);
}
