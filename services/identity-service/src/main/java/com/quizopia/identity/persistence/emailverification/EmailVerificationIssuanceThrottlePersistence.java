package com.quizopia.identity.persistence.emailverification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class EmailVerificationIssuanceThrottlePersistence {
    private final JdbcTemplate jdbcTemplate;

    public EmailVerificationIssuanceThrottlePersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockExactEmail(String exactEmail) {
        Objects.requireNonNull(exactEmail, "exactEmail");
        jdbcTemplate.update(
                "INSERT INTO email_verification_issuance_guard (email) VALUES (?) ON CONFLICT (email) DO NOTHING",
                exactEmail);
        jdbcTemplate.queryForObject(
                "SELECT email FROM email_verification_issuance_guard WHERE email = ? FOR UPDATE",
                String.class,
                exactEmail);
    }

    public Optional<UUID> tryRecord(String exactEmail, Instant issuedAt, Instant windowStart, int maximumIssuances) {
        Objects.requireNonNull(exactEmail, "exactEmail");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(windowStart, "windowStart");
        lockExactEmail(exactEmail);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_issuance "
                        + "WHERE email = ? AND issued_at > ? AND issued_at <= ?",
                Long.class,
                exactEmail,
                Timestamp.from(windowStart),
                Timestamp.from(issuedAt));
        if (count == null || count >= maximumIssuances) {
            return Optional.empty();
        }
        UUID issuanceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO email_verification_issuance (id, email, issued_at) VALUES (?, ?, ?)",
                issuanceId,
                exactEmail,
                Timestamp.from(issuedAt));
        return Optional.of(issuanceId);
    }
}
