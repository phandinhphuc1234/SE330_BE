package com.vn.dto.staff.member.internal;

import java.math.BigDecimal;

// Read-model nội bộ dùng để gom các số liệu tổng hợp của một member cho màn staff.
// Class này không phải JPA entity và không được trả thẳng ra API.
public record StaffMemberStats(
        long activeLoansCount,
        long openLoansCount,
        long overdueLoansCount,
        long borrowHistoryCount,
        long activeHoldsCount,
        BigDecimal unpaidFineTotal
) {
    // Giá trị mặc định cho member chưa có borrow/hold/fine nào.
    public static StaffMemberStats empty() {
        return new StaffMemberStats(0, 0, 0, 0, 0, BigDecimal.ZERO);
    }

    // Tạo bản copy mới với nhóm số liệu borrow đã được cập nhật, giữ nguyên hold/fine hiện có.
    public StaffMemberStats withBorrowCounts(long activeLoansCount,
                                             long openLoansCount,
                                             long overdueLoansCount,
                                             long borrowHistoryCount) {
        return new StaffMemberStats(
                activeLoansCount,
                openLoansCount,
                overdueLoansCount,
                borrowHistoryCount,
                activeHoldsCount,
                unpaidFineTotal
        );
    }

    public StaffMemberStats plusLoanCounts(long activeLoansCount,
                                           long openLoansCount,
                                           long overdueLoansCount,
                                           long borrowHistoryCount) {
        return new StaffMemberStats(
                this.activeLoansCount + activeLoansCount,
                this.openLoansCount + openLoansCount,
                this.overdueLoansCount + overdueLoansCount,
                this.borrowHistoryCount + borrowHistoryCount,
                activeHoldsCount,
                unpaidFineTotal
        );
    }

    // Tạo bản copy mới với tổng fine chưa thanh toán, giữ nguyên các số liệu còn lại.
    public StaffMemberStats withUnpaidFineTotal(BigDecimal unpaidFineTotal) {
        return new StaffMemberStats(
                activeLoansCount,
                openLoansCount,
                overdueLoansCount,
                borrowHistoryCount,
                activeHoldsCount,
                unpaidFineTotal
        );
    }

    // Tạo bản copy mới với số hold đang hoạt động, giữ nguyên các số liệu còn lại.
    public StaffMemberStats withActiveHoldsCount(long activeHoldsCount) {
        return new StaffMemberStats(
                activeLoansCount,
                openLoansCount,
                overdueLoansCount,
                borrowHistoryCount,
                activeHoldsCount,
                unpaidFineTotal
        );
    }
}
