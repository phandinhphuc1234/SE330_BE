package com.vn.payment.service.provider;

import com.vn.payment.dto.provider.ProviderCallbackRequest;
import com.vn.payment.dto.provider.ProviderCallbackVerificationResult;
import com.vn.payment.dto.provider.ProviderPaymentCreateRequest;
import com.vn.payment.dto.provider.ProviderPaymentCreateResult;
import com.vn.payment.dto.provider.ProviderReturnRequest;
import com.vn.payment.dto.provider.ProviderReturnResult;
import com.vn.payment.enums.PaymentProvider;

public interface PaymentProviderClient {

    PaymentProvider supports();

    ProviderPaymentCreateResult createPayment(ProviderPaymentCreateRequest request);

    ProviderCallbackVerificationResult verifyCallback(ProviderCallbackRequest request);

    ProviderReturnResult parseReturn(ProviderReturnRequest request);
}
