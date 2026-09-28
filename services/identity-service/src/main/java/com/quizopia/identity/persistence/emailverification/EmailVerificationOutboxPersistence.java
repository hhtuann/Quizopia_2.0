package com.quizopia.identity.persistence.emailverification;

import com.quizopia.identity.security.outbox.EncryptedOutboxPayload;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class EmailVerificationOutboxPersistence {
    private final JdbcTemplate jdbcTemplate;

    public EmailVerificationOutboxPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void enqueue(
            UUID jobId,
            UUID issuanceId,
            UUID userId,
            String exactRecipientEmail,
            String templateType,
            Instant otpExpiresAt,
            Instant createdAt,
            EncryptedOutboxPayload payload) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(issuanceId, "issuanceId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(exactRecipientEmail, "exactRecipientEmail");
        Objects.requireNonNull(templateType, "templateType");
        Objects.requireNonNull(otpExpiresAt, "otpExpiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(payload, "payload");
        jdbcTemplate.update(
                "INSERT INTO email_verification_email_outbox "
                        + "(id, issuance_id, user_id, recipient_email, template_type, otp_expires_at, state, attempt_count, "
                        + "next_attempt_at, key_version, payload_format_version, ciphertext, nonce, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?, ?, ?)",
                jobId,
                issuanceId,
                userId,
                exactRecipientEmail,
                templateType,
                Timestamp.from(otpExpiresAt),
                Timestamp.from(createdAt),
                payload.keyVersion(),
                payload.payloadFormatVersion(),
                payload.ciphertext(),
                payload.nonce(),
                Timestamp.from(createdAt));
    }

    public int terminalizeActiveForUser(UUID userId, String failureCategory, Instant terminalAt) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(failureCategory, "failureCategory");
        Objects.requireNonNull(terminalAt, "terminalAt");
        return jdbcTemplate.update(
                "UPDATE email_verification_email_outbox "
                        + "SET state = 'FAILED', ciphertext = NULL, nonce = NULL, claim_owner = NULL, "
                        + "claim_expires_at = NULL, terminal_at = ?, failure_category = ? "
                        + "WHERE user_id = ? AND state IN ('PENDING', 'CLAIMED')",
                Timestamp.from(terminalAt),
                failureCategory,
                userId);
    }

    public int terminalizeActiveForOtherUsersByEmail(
            String exactEmail, UUID verifiedOwnerUserId, String failureCategory, Instant terminalAt) {
        Objects.requireNonNull(exactEmail, "exactEmail");
        Objects.requireNonNull(verifiedOwnerUserId, "verifiedOwnerUserId");
        Objects.requireNonNull(failureCategory, "failureCategory");
        Objects.requireNonNull(terminalAt, "terminalAt");
        return jdbcTemplate.update(
                "UPDATE email_verification_email_outbox "
                        + "SET state = 'FAILED', ciphertext = NULL, nonce = NULL, claim_owner = NULL, "
                        + "claim_expires_at = NULL, terminal_at = ?, failure_category = ? "
                        + "WHERE recipient_email = ? AND user_id <> ? AND state IN ('PENDING', 'CLAIMED')",
                Timestamp.from(terminalAt),
                failureCategory,
                exactEmail,
                verifiedOwnerUserId);
    }
}
