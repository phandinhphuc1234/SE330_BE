package com.vn.service.impl.circulation.overdue;

public record OverdueMarkResult(
        boolean success
) {

    public static OverdueMarkResult succeeded() {
        return new OverdueMarkResult(true);
    }

    public static OverdueMarkResult skipped() {
        return new OverdueMarkResult(false);
    }
}
