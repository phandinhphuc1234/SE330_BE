package com.vn.service.impl.circulation.autorenewal;

import com.vn.enums.AutoRenewalResultCode;

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
