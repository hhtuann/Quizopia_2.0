package com.quizopia.identity.infrastructure.email;

import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDelivery;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDeliveryException;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailMessage;
import java.util.Objects;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public final class SpringMailVerificationEmailDelivery implements VerificationEmailDelivery {
    private final JavaMailSender mailSender;
    private final VerificationEmailTemplate template;
    private final String fromAddress;

    public SpringMailVerificationEmailDelivery(
            JavaMailSender mailSender, VerificationEmailTemplate template, String fromAddress) {
        this.mailSender = Objects.requireNonNull(mailSender, "mailSender");
        this.template = Objects.requireNonNull(template, "template");
        this.fromAddress = requireText(fromAddress, "fromAddress");
    }

    @Override
    public void send(VerificationEmailMessage message) {
        Objects.requireNonNull(message, "message");
        var rendered = template.render(message);
        var mail = new SimpleMailMessage();
        mail.setFrom(fromAddress);
        mail.setTo(message.exactRecipientEmail());
        mail.setSubject(rendered.subject());
        mail.setText(rendered.body());
        try {
            mailSender.send(mail);
        } catch (MailException exception) {
            throw new VerificationEmailDeliveryException(classify(exception));
        }
    }

    private static VerificationEmailDeliveryException.Category classify(MailException exception) {
        if (exception instanceof MailAuthenticationException
                || exception instanceof MailParseException
                || exception instanceof MailPreparationException) {
            return VerificationEmailDeliveryException.Category.SMTP_PERMANENT_FAILURE;
        }
        return VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
