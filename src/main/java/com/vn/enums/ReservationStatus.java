package com.vn.enums;

import java.util.List;

public enum ReservationStatus {
    WAITING,
    NOTIFIED,
    READY_FOR_PICKUP,
    FULFILLED,
    CANCELLED,
    EXPIRED;

    // Holds that should block renewal because another member is waiting for this title.
    public static List<ReservationStatus> activeStatuses() {
        return List.of(WAITING, NOTIFIED, READY_FOR_PICKUP);
    }
}
