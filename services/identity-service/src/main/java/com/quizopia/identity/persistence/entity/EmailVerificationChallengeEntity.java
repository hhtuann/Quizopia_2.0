package com.quizopia.identity.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_challenge")
public class EmailVerificationChallengeEntity {
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccountEntity user;

    @Column(name = "otp_hash", nullable = false, columnDefinition = "text")
    private String otpHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resend_not_before", nullable = false)
    private Instant resendNotBefore;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    protected EmailVerificationChallengeEntity() {}

    public EmailVerificationChallengeEntity(UserAccountEntity user) {
        this.user = user;
    }

    public void replace(String otpHash, Instant issuedAt, Instant expiresAt, Instant resendNotBefore, int maxAttempts) {
        this.otpHash = otpHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.resendNotBefore = resendNotBefore;
        this.maxAttempts = maxAttempts;
        this.failedAttempts = 0;
    }

    public void recordFailedAttempt() {
        if (attemptsExhausted()) {
            throw new IllegalStateException("Email verification attempts already exhausted");
        }
        failedAttempts++;
    }

    public boolean attemptsExhausted() {
        return failedAttempts >= maxAttempts;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getResendNotBefore() {
        return resendNotBefore;
    }
}
