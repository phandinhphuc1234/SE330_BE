package com.vn.service;

import com.vn.service.impl.EmailServiceImpl;
import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailServiceImplTest {

    @Test
    void sendVerificationEmail_shouldUseConfiguredMailFromAddress() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);
        MimeMessage message = new MimeMessage((jakarta.mail.Session) null);

        when(mailSender.createMimeMessage()).thenReturn(message);
        when(templateEngine.process(any(String.class), any(IContext.class))).thenReturn("<p>Hello</p>");
        doAnswer(invocation -> null).when(mailSender).send(message);

        EmailServiceImpl emailService = new EmailServiceImpl(mailSender, templateEngine);
        ReflectionTestUtils.setField(emailService, "baseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(emailService, "fromEmail", "onboarding@resend.dev");

        emailService.sendVerificationEmail(1L, "member@example.com", "Member", "token-123");

        Address[] from = message.getFrom();
        assertThat(from).hasSize(1);
        assertThat(((InternetAddress) from[0]).getAddress()).isEqualTo("onboarding@resend.dev");
    }
}
