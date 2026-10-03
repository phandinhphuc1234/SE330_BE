package com.vn.notification.service.impl.delivery;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class NotificationWorkerIdentity {

    private static final int MAX_INSTANCE_PREFIX_LENGTH = 50;

    private final String value;

    public NotificationWorkerIdentity() {
        String instance = Optional.ofNullable(System.getenv("HOSTNAME"))
                .orElseGet(() -> Optional.ofNullable(System.getenv("COMPUTERNAME")).orElse("local"))
                .strip();
        String prefix = instance.substring(0, Math.min(instance.length(), MAX_INSTANCE_PREFIX_LENGTH));
        this.value = prefix + "-" + UUID.randomUUID();
    }

    public String value() {
        return value;
    }
}
