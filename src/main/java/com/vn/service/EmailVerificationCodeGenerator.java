package com.vn.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;

@Component
public class EmailVerificationCodeGenerator {

    private static final int CODE_BOUND = 1_000_000_000;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        return String.format(Locale.ROOT, "%09d", secureRandom.nextInt(CODE_BOUND));
    }
}
