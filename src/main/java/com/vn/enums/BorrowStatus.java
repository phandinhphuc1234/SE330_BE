package com.vn.enums;

import java.util.List;

public enum BorrowStatus {
    BORROWED,
    RETURNED,
    OVERDUE,
    LOST;

    // Records that still represent a copy outside normal shelf circulation.
    public static List<BorrowStatus> openStatuses() {
        return List.of(BORROWED, OVERDUE, LOST);
    }

    // Records counted against a member's current borrow limit.
    public static List<BorrowStatus> activeStatuses() {
        return List.of(BORROWED, OVERDUE);
    }

    public boolean isRenewable() {
        return this == BORROWED;
    }
}
