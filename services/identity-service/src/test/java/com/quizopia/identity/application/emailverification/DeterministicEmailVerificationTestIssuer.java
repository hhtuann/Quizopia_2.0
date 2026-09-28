package com.quizopia.identity.application.emailverification;

import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.util.UUID;

/** Test-only access to deterministic OTP and policy seams. */
public final class DeterministicEmailVerificationTestIssuer {
    private final EmailVerificationTransaction transaction;

    public DeterministicEmailVerificationTestIssuer(EmailVerificationTransaction transaction) {
        this.transaction = transaction;
    }

    public EmailVerificationIssueStatus issueChallenge(
            UUID userId, RawEmailVerificationOtp rawOtp, EmailVerificationPolicy policy) {
        return transaction.issueChallenge(userId, rawOtp, policy);
    }
}
