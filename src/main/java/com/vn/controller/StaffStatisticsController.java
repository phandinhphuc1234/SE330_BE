package com.vn.controller;

import com.vn.repository.BorrowRecordRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/staff/statistics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
public class StaffStatisticsController {

    private final BorrowRecordRepository borrowRecordRepository;

    @GetMapping("/borrows")
    public ResponseEntity<?> getBorrowStatistics(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String filterType,
            @RequestParam(required = false) String filterValue,
            @RequestParam(required = false) String language) {

        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate   = LocalDate.parse(to);

        // Giới hạn 14 ngày
        if (ChronoUnit.DAYS.between(fromDate, toDate) > 20) {
            return ResponseEntity.badRequest().body("Max range is 21 days");
        }

        ZoneId vnZone = ZoneId.of("Asia/Ho_Chi_Minh");
        Instant fromInstant = fromDate.atStartOfDay(vnZone).toInstant();
        Instant toInstant   = toDate.plusDays(1).atStartOfDay(vnZone).toInstant();

        // Query theo ngày — bạn cần thêm 2 query vào BorrowRecordRepository (xem bên dưới)
        List<Object[]> borrowedRows = borrowRecordRepository
            .countBorrowedPerDay(fromInstant, toInstant, filterType, filterValue, language);
        List<Object[]> returnedRows = borrowRecordRepository
            .countReturnedPerDay(fromInstant, toInstant, filterType, filterValue, language);

        // Build map date -> count
        Map<String, Integer> borrowMap = new HashMap<>();
        for (Object[] row : borrowedRows) {
            borrowMap.put(row[0].toString(), ((Number) row[1]).intValue());
        }
        Map<String, Integer> returnMap = new HashMap<>();
        for (Object[] row : returnedRows) {
            returnMap.put(row[0].toString(), ((Number) row[1]).intValue());
        }

        // Build days list
        List<Map<String, Object>> days = new ArrayList<>();
        LocalDate cur = fromDate;
        int totalBorrowed = 0, totalReturned = 0;
        String peakDate = null; int peakCount = 0;

        while (!cur.isAfter(toDate)) {
            String d = cur.toString();
            int b = borrowMap.getOrDefault(d, 0);
            int r = returnMap.getOrDefault(d, 0);
            totalBorrowed += b;
            totalReturned += r;
            if (b > peakCount) { peakCount = b; peakDate = d; }
            days.add(Map.of("date", d, "borrowed", b, "returned", r));
            cur = cur.plusDays(1);
        }

        return ResponseEntity.ok(Map.of(
            "days", days,
            "totalBorrowed", totalBorrowed,
            "totalReturned", totalReturned,
            "netOnLoan", totalBorrowed - totalReturned,
            "peakBorrowDate", peakDate != null ? peakDate : "",
            "peakBorrowCount", peakCount
        ));
    }
}
