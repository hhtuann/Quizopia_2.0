package com.quizopia.identity.persistence.emailverification;

import com.quizopia.identity.application.emailverification.delivery.ClaimedEmailVerificationOutboxJob;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxStore;
import com.quizopia.identity.configuration.EmailOutboxDispatcherProperties;
import com.quizopia.identity.security.outbox.EncryptedOutboxPayload;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class PostgresEmailVerificationOutboxStore implements EmailVerificationOutboxStore {
    // Every operation is one atomic PostgreSQL statement. The dedicated dispatcher pool therefore
    // uses JDBC autocommit and never opens an unrelated primary/JPA transaction.
    private final JdbcTemplate jdbcTemplate;

    public PostgresEmailVerificationOutboxStore(EmailOutboxDispatcherDatabase dispatcherDatabase) {
        this.jdbcTemplate =
                Objects.requireNonNull(dispatcherDatabase, "dispatcherDatabase").jdbcTemplate();
    }

    @Override
    public List<ClaimedEmailVerificationOutboxJob> claimDue(
            String claimOwner, Instant now, Instant claimExpiresAt, int batchSize) {
        Objects.requireNonNull(claimOwner, "claimOwner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
        if (claimOwner.isBlank()
                || claimOwner.length() > 128
                || !claimExpiresAt.isAfter(now)
                || batchSize <= 0
                || batchSize > EmailOutboxDispatcherProperties.MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid email outbox claim parameters");
        }
        return jdbcTemplate.query(
                """
                WITH due AS (
                    SELECT id
                    FROM email_verification_email_outbox
                    WHERE (state = 'PENDING' AND next_attempt_at <= ?)
                       OR (state = 'CLAIMED' AND claim_expires_at <= ?)
                    ORDER BY COALESCE(claim_expires_at, next_attempt_at), id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE email_verification_email_outbox job
                SET state = 'CLAIMED', claim_owner = ?, claim_expires_at = ?
                FROM due
                WHERE job.id = due.id
                RETURNING job.id, job.issuance_id, job.user_id,
                          (SELECT account.username FROM user_account account WHERE account.id = job.user_id) AS username,
                          job.recipient_email, job.template_type,
                          job.otp_expires_at, job.attempt_count, job.ciphertext, job.nonce,
                          job.key_version, job.payload_format_version
                """,
                (row, rowNumber) -> new ClaimedEmailVerificationOutboxJob(
                        row.getObject("id", UUID.class),
                        row.getObject("issuance_id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        row.getString("username"),
                        row.getString("recipient_email"),
                        row.getString("template_type"),
                        row.getTimestamp("otp_expires_at").toInstant(),
                        row.getInt("attempt_count"),
                        new EncryptedOutboxPayload(
                                row.getBytes("ciphertext"),
                                row.getBytes("nonce"),
                                row.getString("key_version"),
                                row.getInt("payload_format_version"))),
                Timestamp.from(now),
                Timestamp.from(now),
                batchSize,
                claimOwner,
                Timestamp.from(claimExpiresAt));
    }

    @Override
    public boolean renewClaim(UUID jobId, String claimOwner, Instant now, Instant claimExpiresAt) {
        Objects.requireNonNull(now, "now");
        if (!claimExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Claim expiry must be after now");
        }
        return jdbcTemplate.update(
                        "UPDATE email_verification_email_outbox SET claim_expires_at = ? "
                                + "WHERE id = ? AND state = 'CLAIMED' AND claim_owner = ?",
                        Timestamp.from(claimExpiresAt),
                        jobId,
                        claimOwner)
                == 1;
    }

    @Override
    public OptionalInt reserveAttempt(
            UUID jobId, String claimOwner, Instant now, Instant claimExpiresAt, int maximumAttempts) {
        Objects.requireNonNull(now, "now");
        if (!claimExpiresAt.isAfter(now) || maximumAttempts <= 0) {
            throw new IllegalArgumentException("Invalid delivery-attempt reservation parameters");
        }
        List<Integer> attempts = jdbcTemplate.queryForList(
                """
                UPDATE email_verification_email_outbox job
                SET attempt_count = attempt_count + 1, claim_expires_at = ?
                WHERE job.id = ?
                  AND job.state = 'CLAIMED'
                  AND job.claim_owner = ?
                  AND job.attempt_count < ?
                  AND EXISTS (
                      SELECT 1
                      FROM email_verification_challenge challenge
                      JOIN user_account account ON account.id = challenge.user_id
                      WHERE challenge.user_id = job.user_id
                        AND challenge.current_issuance_id = job.issuance_id
                        AND account.account_status = 'PENDING_EMAIL_VERIFICATION'
                        AND account.email_verified_at IS NULL
                        AND NOT EXISTS (
                            SELECT 1
                            FROM user_account verified_owner
                            WHERE verified_owner.email = job.recipient_email
                              AND verified_owner.email_verified_at IS NOT NULL
                              AND verified_owner.id <> job.user_id
                        )
                  )
                RETURNING attempt_count
                """,
                Integer.class,
                Timestamp.from(claimExpiresAt),
                jobId,
                claimOwner,
                maximumAttempts);
        return attempts.isEmpty() ? OptionalInt.empty() : OptionalInt.of(attempts.getFirst());
    }

    @Override
    public boolean isDeliverable(UUID jobId, String claimOwner) {
        Boolean deliverable = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM email_verification_email_outbox job
                    JOIN email_verification_challenge challenge
                      ON challenge.user_id = job.user_id
                     AND challenge.current_issuance_id = job.issuance_id
                    JOIN user_account account ON account.id = job.user_id
                    WHERE job.id = ?
                      AND job.state = 'CLAIMED'
                      AND job.claim_owner = ?
                      AND account.account_status = 'PENDING_EMAIL_VERIFICATION'
                      AND account.email_verified_at IS NULL
                      AND NOT EXISTS (
                          SELECT 1
                          FROM user_account verified_owner
                          WHERE verified_owner.email = job.recipient_email
                            AND verified_owner.email_verified_at IS NOT NULL
                            AND verified_owner.id <> job.user_id
                      )
                )
                """,
                Boolean.class,
                jobId,
                claimOwner);
        return Boolean.TRUE.equals(deliverable);
    }

    @Override
    public boolean markSent(UUID jobId, String claimOwner, Instant sentAt) {
        return jdbcTemplate.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'SENT', ciphertext = NULL, nonce = NULL, "
                                + "claim_owner = NULL, claim_expires_at = NULL, sent_at = ?, terminal_at = ?, failure_category = NULL "
                                + "WHERE id = ? AND state = 'CLAIMED' AND claim_owner = ?",
                        Timestamp.from(sentAt),
                        Timestamp.from(sentAt),
                        jobId,
                        claimOwner)
                == 1;
    }

    @Override
    public boolean scheduleRetry(UUID jobId, String claimOwner, String failureCategory, Instant nextAttemptAt) {
        return jdbcTemplate.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'PENDING', next_attempt_at = ?, "
                                + "claim_owner = NULL, claim_expires_at = NULL, failure_category = ? "
                                + "WHERE id = ? AND state = 'CLAIMED' AND claim_owner = ?",
                        Timestamp.from(nextAttemptAt),
                        failureCategory,
                        jobId,
                        claimOwner)
                == 1;
    }

    @Override
    public boolean markFailed(UUID jobId, String claimOwner, String failureCategory, Instant terminalAt) {
        return jdbcTemplate.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'FAILED', ciphertext = NULL, nonce = NULL, "
                                + "claim_owner = NULL, claim_expires_at = NULL, terminal_at = ?, failure_category = ? "
                                + "WHERE id = ? AND state = 'CLAIMED' AND claim_owner = ?",
                        Timestamp.from(terminalAt),
                        failureCategory,
                        jobId,
                        claimOwner)
                == 1;
    }

    @Override
    public boolean markAttemptsExhausted(UUID jobId, String claimOwner, Instant terminalAt) {
        return jdbcTemplate.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'FAILED', ciphertext = NULL, nonce = NULL, claim_owner = NULL, "
                                + "claim_expires_at = NULL, terminal_at = ?, failure_category = 'MAXIMUM_ATTEMPTS_EXHAUSTED' "
                                + "WHERE id = ? AND state = 'CLAIMED' AND claim_owner = ?",
                        Timestamp.from(terminalAt),
                        jobId,
                        claimOwner)
                == 1;
    }

    @Override
    public boolean markExpired(UUID jobId, String claimOwner, Instant terminalAt) {
        return jdbcTemplate.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'EXPIRED', ciphertext = NULL, nonce = NULL, claim_owner = NULL, "
                                + "claim_expires_at = NULL, terminal_at = ?, failure_category = 'OTP_EXPIRED' "
                                + "WHERE id = ? AND state = 'CLAIMED' AND claim_owner = ?",
                        Timestamp.from(terminalAt),
                        jobId,
                        claimOwner)
                == 1;
    }

    @Override
    public boolean markExpiredAfterAttempt(UUID jobId, String claimOwner, Instant terminalAt) {
        return jdbcTemplate.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'EXPIRED', ciphertext = NULL, nonce = NULL, "
                                + "claim_owner = NULL, claim_expires_at = NULL, terminal_at = ?, failure_category = 'OTP_EXPIRED' "
                                + "WHERE id = ? AND state = 'CLAIMED' AND claim_owner = ?",
                        Timestamp.from(terminalAt),
                        jobId,
                        claimOwner)
                == 1;
    }

    @Override
    public boolean markObsolete(UUID jobId, String claimOwner, Instant terminalAt) {
        return jdbcTemplate.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'FAILED', ciphertext = NULL, nonce = NULL, claim_owner = NULL, "
                                + "claim_expires_at = NULL, terminal_at = ?, failure_category = 'OTP_OBSOLETE' "
                                + "WHERE id = ? AND state = 'CLAIMED' AND claim_owner = ?",
                        Timestamp.from(terminalAt),
                        jobId,
                        claimOwner)
                == 1;
    }
}
