package com.quizopia.identity.security.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class OutboxPayloadBinding {
    private final UUID jobId;
    private final String exactRecipientEmail;
    private final String templateType;
    private final Instant otpExpiresAt;
    private final int payloadFormatVersion;

    public OutboxPayloadBinding(
            UUID jobId,
            String exactRecipientEmail,
            String templateType,
            Instant otpExpiresAt,
            int payloadFormatVersion) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.exactRecipientEmail = requireText(exactRecipientEmail, "exactRecipientEmail");
        this.templateType = requireText(templateType, "templateType");
        this.otpExpiresAt = Objects.requireNonNull(otpExpiresAt, "otpExpiresAt");
        if (otpExpiresAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("otpExpiresAt must use PostgreSQL microsecond precision");
        }
        if (payloadFormatVersion <= 0) {
            throw new IllegalArgumentException("payloadFormatVersion must be positive");
        }
        this.payloadFormatVersion = payloadFormatVersion;
    }

    public UUID jobId() {
        return jobId;
    }

    public String exactRecipientEmail() {
        return exactRecipientEmail;
    }

    public String templateType() {
        return templateType;
    }

    public Instant otpExpiresAt() {
        return otpExpiresAt;
    }

    public int payloadFormatVersion() {
        return payloadFormatVersion;
    }

    @Override
    public String toString() {
        return "OutboxPayloadBinding{jobId=" + jobId
                + ", recipientPresent=true, templateType=" + templateType
                + ", otpExpiresAt=" + otpExpiresAt
                + ", payloadFormatVersion=" + payloadFormatVersion + '}';
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
