package com.vn.service.payment.provider;

import com.vn.enums.PaymentProvider;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentProviderClientFactory {

    private final Map<PaymentProvider, PaymentProviderClient> clients;
    //
    public PaymentProviderClientFactory(List<PaymentProviderClient> clients) {
        EnumMap<PaymentProvider, PaymentProviderClient> mappedClients = new EnumMap<>(PaymentProvider.class);
        for (PaymentProviderClient client : clients) {
            PaymentProvider provider = client.supports();
            if (provider == null) {
                throw new IllegalStateException("PaymentProviderClient.supports() must not return null");
            }
            if (mappedClients.put(provider, client) != null) {
                throw new IllegalStateException("Duplicate PaymentProviderClient for provider " + provider);
            }
        }
        this.clients = Collections.unmodifiableMap(mappedClients);
    }

    public PaymentProviderClient get(PaymentProvider provider) {
        PaymentProviderClient client = clients.get(provider);
        if (client == null) {
            throw new AppException(ErrorCode.UNSUPPORTED_PAYMENT_PROVIDER);
        }
        return client;
    }
}
