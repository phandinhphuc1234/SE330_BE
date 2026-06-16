package com.vn.service.payment.provider;

import com.vn.dto.payment.provider.ProviderCallbackRequest;
import com.vn.dto.payment.provider.ProviderCallbackVerificationResult;
import com.vn.dto.payment.provider.ProviderPaymentCreateRequest;
import com.vn.dto.payment.provider.ProviderPaymentCreateResult;
import com.vn.dto.payment.provider.ProviderReturnRequest;
import com.vn.dto.payment.provider.ProviderReturnResult;
import com.vn.enums.PaymentProvider;

public interface PaymentProviderClient {

    PaymentProvider supports();

    ProviderPaymentCreateResult createPayment(ProviderPaymentCreateRequest request);

    ProviderCallbackVerificationResult verifyCallback(ProviderCallbackRequest request);

    ProviderReturnResult parseReturn(ProviderReturnRequest request);
}
