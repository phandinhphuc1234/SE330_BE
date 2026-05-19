package com.vn.service.impl.circulation.holdexpiry;

public record HoldExpiryResult(boolean success) {

    public static HoldExpiryResult expired() {
        return new HoldExpiryResult(true);
    }

    public static HoldExpiryResult skipped() {
        return new HoldExpiryResult(false);
    }
}
