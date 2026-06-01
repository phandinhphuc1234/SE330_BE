package com.vn.service.impl.staff.member;

import com.vn.dto.staff.member.internal.StaffMemberStats;
import com.vn.entity.Member;
import com.vn.enums.BorrowStatus;
import com.vn.enums.ReservationStatus;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StaffMemberStatsLoader {

    private final BorrowRecordRepository borrowRecordRepository;
    private final ReservationRepository reservationRepository;

    // Load thống kê theo batch cho một trang member để tránh query từng member một.
    public Map<Long, StaffMemberStats> loadStats(List<Member> members, Instant now) {
        List<Long> memberIds = members.stream()
                .map(Member::getId)
                .toList();

        if (memberIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, StaffMemberStats> statsByMemberId = new HashMap<>();
        for (Long memberId : memberIds) {
            statsByMemberId.put(memberId, StaffMemberStats.empty());
        }

        applyBorrowCounts(statsByMemberId, memberIds, now);
        applyUnpaidFineTotals(statsByMemberId, memberIds);
        applyActiveHoldCounts(statsByMemberId, memberIds);
        return statsByMemberId;
    }

    // Lấy các số liệu từ borrow_records: active/open/overdue/history.
    private void applyBorrowCounts(Map<Long, StaffMemberStats> statsByMemberId,
                                   Collection<Long> memberIds,
                                   Instant now) {
        List<Object[]> rows = borrowRecordRepository.summarizeBorrowCountsByMemberIds(
                memberIds,
                BorrowStatus.activeStatuses(),
                BorrowStatus.openStatuses(),
                BorrowStatus.OVERDUE,
                BorrowStatus.BORROWED,
                now
        );

        for (Object[] row : rows) {
            Long memberId = (Long) row[0];
            StaffMemberStats current = statsByMemberId.getOrDefault(memberId, StaffMemberStats.empty());
            statsByMemberId.put(memberId, current.withBorrowCounts(
                    toLong(row[1]),
                    toLong(row[2]),
                    toLong(row[3]),
                    toLong(row[4])
            ));
        }
    }

    // Lấy tổng tiền phạt chưa paid và chưa waived từ borrow_records.
    private void applyUnpaidFineTotals(Map<Long, StaffMemberStats> statsByMemberId,
                                       Collection<Long> memberIds) {
        List<Object[]> rows = borrowRecordRepository.summarizeUnpaidFineTotalsByMemberIds(memberIds);
        for (Object[] row : rows) {
            Long memberId = (Long) row[0];
            StaffMemberStats current = statsByMemberId.getOrDefault(memberId, StaffMemberStats.empty());
            statsByMemberId.put(memberId, current.withUnpaidFineTotal(toBigDecimal(row[1])));
        }
    }

    // Lấy số reservation/hold đang hoạt động của từng member.
    private void applyActiveHoldCounts(Map<Long, StaffMemberStats> statsByMemberId,
                                       Collection<Long> memberIds) {
        List<Object[]> rows = reservationRepository.summarizeActiveHoldCountsByMemberIds(
                memberIds,
                ReservationStatus.activeStatuses()
        );
        for (Object[] row : rows) {
            Long memberId = (Long) row[0];
            StaffMemberStats current = statsByMemberId.getOrDefault(memberId, StaffMemberStats.empty());
            statsByMemberId.put(memberId, current.withActiveHoldsCount(toLong(row[1])));
        }
    }

    // JPQL aggregate trả về Number tùy dialect, nên ép về long ở một điểm duy nhất.
    private long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    // JPQL sum với BigDecimal thường trả BigDecimal, nhưng vẫn fallback cho Number để an toàn.
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return BigDecimal.valueOf(((Number) value).doubleValue());
    }
}
