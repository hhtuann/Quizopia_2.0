package com.quizopia.identity.application.emailverification.delivery;

import com.quizopia.identity.security.outbox.EncryptedOutboxPayload;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedEmailVerificationOutboxJob(
        UUID id,
        UUID issuanceId,
        UUID userId,
        String exactUsername,
        String exactRecipientEmail,
        String templateType,
        Instant otpExpiresAt,
        int attemptCount,
        EncryptedOutboxPayload encryptedPayload) {
    public ClaimedEmailVerificationOutboxJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(issuanceId, "issuanceId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(exactUsername, "exactUsername");
        Objects.requireNonNull(exactRecipientEmail, "exactRecipientEmail");
        Objects.requireNonNull(templateType, "templateType");
        Objects.requireNonNull(otpExpiresAt, "otpExpiresAt");
        Objects.requireNonNull(encryptedPayload, "encryptedPayload");
        if (exactUsername.isBlank() || exactRecipientEmail.isBlank() || templateType.isBlank() || attemptCount < 0) {
            throw new IllegalArgumentException("Claimed outbox metadata is invalid");
        }
    }

    @Override
    public String toString() {
        return "ClaimedEmailVerificationOutboxJob{id=" + id
                + ", issuanceId=" + issuanceId
                + ", userId=" + userId
                + ", usernamePresent=true, recipientPresent=true, templateType=" + templateType
                + ", otpExpiresAt=" + otpExpiresAt
                + ", attemptCount=" + attemptCount
                + ", encryptedPayload=[REDACTED]}";
    }
}
