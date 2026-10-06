package com.vn.loan.service.impl.autorenewal;

import com.vn.loan.enums.AutoRenewalResultCode;

public record AutoRenewalResult(
        boolean success,
        AutoRenewalResultCode code
) {

    public static AutoRenewalResult succeeded() {
        return new AutoRenewalResult(true, AutoRenewalResultCode.SUCCESS);
    }

    public static AutoRenewalResult failed(AutoRenewalResultCode code) {
        return new AutoRenewalResult(false, code);
    }
}
