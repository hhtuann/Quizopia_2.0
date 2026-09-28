package com.quizopia.identity.application.emailverification;

import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class UsernameEmailVerificationService {
    private static final int MAX_USERNAME_LENGTH = 255;

    private final UserAccountRepository userAccountRepository;
    private final EmailVerificationRequestService requestService;
    private final EmailVerificationService verificationService;
    private final EmailVerificationTimingProtector timingProtector;

    public UsernameEmailVerificationService(
            UserAccountRepository userAccountRepository,
            EmailVerificationRequestService requestService,
            EmailVerificationService verificationService,
            EmailVerificationTimingProtector timingProtector) {
        this.userAccountRepository = userAccountRepository;
        this.requestService = requestService;
        this.verificationService = verificationService;
        this.timingProtector = timingProtector;
    }

    public EmailVerificationRequestStatus request(String exactUsername) {
        return resolveUserId(exactUsername)
                .map(requestService::request)
                .orElse(EmailVerificationRequestStatus.NOT_ELIGIBLE);
    }

    public EmailVerificationStatus confirm(String exactUsername, RawEmailVerificationOtp rawOtp) {
        Objects.requireNonNull(rawOtp, "rawOtp");
        var userId = resolveUserId(exactUsername);
        if (userId.isEmpty()) {
            timingProtector.balanceConfirmation(rawOtp);
            return EmailVerificationStatus.NOT_FOUND;
        }
        return verificationService.verify(userId.orElseThrow(), rawOtp);
    }

    private java.util.Optional<UUID> resolveUserId(String exactUsername) {
        Objects.requireNonNull(exactUsername, "exactUsername");
        if (exactUsername.isBlank() || exactUsername.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("Username is invalid");
        }
        return userAccountRepository.findIdByUsername(exactUsername);
    }
}
