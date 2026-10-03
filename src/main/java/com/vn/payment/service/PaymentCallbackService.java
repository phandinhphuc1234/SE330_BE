package com.vn.payment.service;

import com.vn.payment.dto.response.PaymentIpnResponse;
import com.vn.payment.dto.response.PaymentResponse;

import java.util.Map;

public interface PaymentCallbackService {

    // Xử lý VNPAY IPN server-to-server; endpoint public nhưng mọi dữ liệu phải qua signature.
    PaymentIpnResponse handleVnpayIpn(Map<String, String> params, Map<String, String> headers);

    // Fallback cho VNPAY Return URL: endpoint authenticated, verify chữ ký rồi mới cập nhật DB.
    PaymentResponse confirmVnpayReturn(Long memberId, Map<String, String> params, Map<String, String> headers);
}
