package com.vn.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationCodeGeneratorTest {

    private final EmailVerificationCodeGenerator generator = new EmailVerificationCodeGenerator();

    @Test
    void generate_shouldAlwaysReturnExactlyNineDigits() {
        for (int index = 0; index < 100; index++) {
            assertThat(generator.generate()).matches("^\\d{9}$");
        }
    }
}
