package com.quizopia.identity.infrastructure.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDeliveryException;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailMessage;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SpringMailVerificationEmailDeliveryTest {
    private static final RawEmailVerificationOtp OTP = RawEmailVerificationOtp.from("012345");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-23T03:10:00.123456Z");

    @Test
    void rendersAndSendsThroughSpringMail() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var delivery = new SpringMailVerificationEmailDelivery(
                sender, new VerificationEmailTemplate(), "no-reply@quizopia.test");

        delivery.send(new VerificationEmailMessage("person@gmail.com", "person-a", OTP, EXPIRES_AT));

        var mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertEquals("no-reply@quizopia.test", mail.getValue().getFrom());
        assertEquals("person@gmail.com", mail.getValue().getTo()[0]);
        assertEquals(VerificationEmailTemplate.SUBJECT, mail.getValue().getSubject());
        assertEquals(true, mail.getValue().getText().contains(OTP.value()));
        assertEquals(true, mail.getValue().getText().contains("person-a"));
        assertEquals(true, mail.getValue().getText().contains(EXPIRES_AT.toString()));
        assertFalse(new VerificationEmailTemplate()
                .render(new VerificationEmailMessage("person@gmail.com", "person-a", OTP, EXPIRES_AT))
                .toString()
                .contains(OTP.value()));
    }

    @Test
    void authenticationFailureIsSanitizedAndPermanent() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailAuthenticationException("credential-secret"))
                .when(sender)
                .send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
        var delivery = new SpringMailVerificationEmailDelivery(
                sender, new VerificationEmailTemplate(), "no-reply@quizopia.test");

        var failure = assertThrows(
                VerificationEmailDeliveryException.class,
                () -> delivery.send(new VerificationEmailMessage("person@gmail.com", "person-a", OTP, EXPIRES_AT)));

        assertEquals(VerificationEmailDeliveryException.Category.SMTP_PERMANENT_FAILURE, failure.category());
        assertFalse(failure.toString().contains("credential-secret"));
        assertFalse(failure.toString().contains(OTP.value()));
    }

    @Test
    void unclassifiedSendFailureIsConservativelyTransientAndSanitized() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("provider-response-secret"))
                .when(sender)
                .send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
        var delivery = new SpringMailVerificationEmailDelivery(
                sender, new VerificationEmailTemplate(), "no-reply@quizopia.test");

        var failure = assertThrows(
                VerificationEmailDeliveryException.class,
                () -> delivery.send(new VerificationEmailMessage("person@gmail.com", "person-a", OTP, EXPIRES_AT)));

        assertEquals(VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE, failure.category());
        assertFalse(failure.toString().contains("provider-response-secret"));
    }
}
