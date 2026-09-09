package com.quizopia.identity.application.emailverification;

import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class EmailVerificationService {
    private final EmailVerificationTransaction transaction;

    public EmailVerificationService(EmailVerificationTransaction transaction) {
        this.transaction = transaction;
    }

    public EmailVerificationIssueStatus issueChallenge(
            UUID userId, RawEmailVerificationOtp rawOtp, EmailVerificationPolicy policy) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(rawOtp, "rawOtp");
        Objects.requireNonNull(policy, "policy");
        return transaction.issueChallenge(userId, rawOtp, policy);
    }

    public EmailVerificationStatus verify(UUID userId, RawEmailVerificationOtp rawOtp) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(rawOtp, "rawOtp");
        return transaction.verify(userId, rawOtp);
    }
}
