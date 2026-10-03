package com.vn.notification.service.impl;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class NotificationPayloadGuard {

    private static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
            "password",
            "secret",
            "token",
            "credential",
            "apikey",
            "privatekey",
            "authorization",
            "cookie"
    );

    public void validate(Map<String, Object> payload) {
        validateValue(payload, "payload");
    }

    private void validateValue(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nestedValue) -> {
                String keyText = String.valueOf(key);
                rejectSensitiveKey(keyText, path);
                validateValue(nestedValue, path + "." + keyText);
            });
            return;
        }
        if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object item : collection) {
                validateValue(item, path + "[" + index + "]");
                index++;
            }
        }
    }

    private void rejectSensitiveKey(String key, String path) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        boolean forbidden = FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
        if (forbidden) {
            throw new IllegalArgumentException(
                    "Sensitive notification payload key is not allowed: " + path + "." + key
            );
        }
    }
}
