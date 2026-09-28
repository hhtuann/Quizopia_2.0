package com.quizopia.identity.application.emailverification;

import com.quizopia.identity.application.activation.VerifiedEmailOwnershipConstraint;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class EmailVerificationService {
    private final EmailVerificationTransaction transaction;

    public EmailVerificationService(EmailVerificationTransaction transaction) {
        this.transaction = transaction;
    }

    EmailVerificationIssueStatus issueGeneratedChallenge(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return transaction.issueGeneratedChallenge(userId);
    }

    public EmailVerificationStatus verify(UUID userId, RawEmailVerificationOtp rawOtp) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(rawOtp, "rawOtp");
        try {
            return transaction.verify(userId, rawOtp);
        } catch (DataIntegrityViolationException exception) {
            if (VerifiedEmailOwnershipConstraint.isViolation(exception)) {
                return EmailVerificationStatus.CONFLICT;
            }
            throw exception;
        }
    }
}
