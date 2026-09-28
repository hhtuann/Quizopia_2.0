package com.quizopia.identity.application.emailverification;

import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class EmailVerificationRequestService {
    private final EmailVerificationService verificationService;

    public EmailVerificationRequestService(EmailVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    public EmailVerificationRequestStatus request(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        EmailVerificationIssueStatus issue = verificationService.issueGeneratedChallenge(userId);
        if (issue == EmailVerificationIssueStatus.COOLDOWN || issue == EmailVerificationIssueStatus.HOURLY_LIMIT) {
            return EmailVerificationRequestStatus.TRY_LATER;
        }
        if (issue != EmailVerificationIssueStatus.ISSUED) {
            return EmailVerificationRequestStatus.NOT_ELIGIBLE;
        }

        return EmailVerificationRequestStatus.REQUEST_ACCEPTED;
    }
}
