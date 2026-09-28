package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.quizopia.identity.application.emailverification.EmailVerificationIssueStatus;
import com.quizopia.identity.application.emailverification.EmailVerificationRequestService;
import com.quizopia.identity.application.emailverification.EmailVerificationRequestStatus;
import com.quizopia.identity.application.emailverification.EmailVerificationService;
import com.quizopia.identity.application.emailverification.EmailVerificationTransaction;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailVerificationRequestServiceTest {
    @Test
    void committedOutboxIssuanceMapsToAcceptedWithoutDeliveryDependency() {
        UUID userId = UUID.randomUUID();
        EmailVerificationTransaction transaction = mock(EmailVerificationTransaction.class);
        when(transaction.issueGeneratedChallenge(userId)).thenReturn(EmailVerificationIssueStatus.ISSUED);
        EmailVerificationRequestService service =
                new EmailVerificationRequestService(new EmailVerificationService(transaction));

        assertEquals(EmailVerificationRequestStatus.REQUEST_ACCEPTED, service.request(userId));
    }

    @Test
    void internalOutcomesRemainEnumerationSafe() {
        for (EmailVerificationIssueStatus issueStatus : EmailVerificationIssueStatus.values()) {
            UUID userId = UUID.randomUUID();
            EmailVerificationTransaction transaction = mock(EmailVerificationTransaction.class);
            when(transaction.issueGeneratedChallenge(userId)).thenReturn(issueStatus);
            EmailVerificationRequestService service =
                    new EmailVerificationRequestService(new EmailVerificationService(transaction));

            EmailVerificationRequestStatus expected =
                    switch (issueStatus) {
                        case ISSUED -> EmailVerificationRequestStatus.REQUEST_ACCEPTED;
                        case COOLDOWN, HOURLY_LIMIT -> EmailVerificationRequestStatus.TRY_LATER;
                        case ALREADY_VERIFIED, NOT_FOUND, CONFLICT -> EmailVerificationRequestStatus.NOT_ELIGIBLE;
                    };
            assertEquals(expected, service.request(userId));
        }
    }
}
