package com.vn.service;

import com.vn.dto.payment.response.PaymentIpnResponse;
import com.vn.dto.payment.response.PaymentResponse;

import java.util.Map;

public interface PaymentCallbackService {

    // Xử lý VNPAY IPN server-to-server; endpoint public nhưng mọi dữ liệu phải qua signature.
    PaymentIpnResponse handleVnpayIpn(Map<String, String> params, Map<String, String> headers);

    // Fallback cho VNPAY Return URL: endpoint authenticated, verify chữ ký rồi mới cập nhật DB.
    PaymentResponse confirmVnpayReturn(Long memberId, Map<String, String> params, Map<String, String> headers);
}
