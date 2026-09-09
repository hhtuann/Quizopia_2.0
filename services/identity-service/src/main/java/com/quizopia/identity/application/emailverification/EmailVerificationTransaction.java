package com.quizopia.identity.application.emailverification;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.activation.TrustedEmailActivationInput;
import com.quizopia.identity.application.activation.TrustedEmailActivationService;
import com.quizopia.identity.application.activation.TrustedEmailActivationStatus;
import com.quizopia.identity.persistence.entity.EmailVerificationChallengeEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.repository.EmailVerificationChallengeRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class EmailVerificationTransaction {
    private final UserAccountRepository userAccountRepository;
    private final EmailVerificationChallengeRepository challengeRepository;
    private final TrustedEmailActivationService activationService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public EmailVerificationTransaction(
            UserAccountRepository userAccountRepository,
            EmailVerificationChallengeRepository challengeRepository,
            TrustedEmailActivationService activationService,
            @Qualifier("serviceClientPasswordEncoder") PasswordEncoder passwordEncoder,
            @Qualifier("identityClock") Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.challengeRepository = challengeRepository;
        this.activationService = activationService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public EmailVerificationIssueStatus issueChallenge(
            UUID userId, RawEmailVerificationOtp rawOtp, EmailVerificationPolicy policy) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(rawOtp, "rawOtp");
        Objects.requireNonNull(policy, "policy");
        // All challenge operations lock user first, including when no challenge exists yet.
        UserAccountEntity user = userAccountRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return EmailVerificationIssueStatus.NOT_FOUND;
        }
        if (isAlreadyVerified(user)) {
            return EmailVerificationIssueStatus.ALREADY_VERIFIED;
        }
        if (!isPendingVerification(user)) {
            return EmailVerificationIssueStatus.CONFLICT;
        }
        EmailVerificationChallengeEntity challenge =
                challengeRepository.findByUserIdForUpdate(userId).orElse(null);
        Instant now = clock.instant();
        if (challenge != null && now.isBefore(challenge.getResendNotBefore())) {
            return EmailVerificationIssueStatus.COOLDOWN;
        }

        String otpHash =
                Objects.requireNonNull(passwordEncoder.encode(rawOtp.value()), "PasswordEncoder returned null");
        if (otpHash.isBlank() || otpHash.startsWith("{noop}")) {
            throw new IllegalStateException("PasswordEncoder must return an encoded OTP");
        }
        if (challenge == null) {
            challenge = new EmailVerificationChallengeEntity(user);
        }
        challenge.replace(
                otpHash, now, now.plus(policy.expiry()), now.plus(policy.resendCooldown()), policy.maxAttempts());
        challengeRepository.saveAndFlush(challenge);
        return EmailVerificationIssueStatus.ISSUED;
    }

    @Transactional
    public EmailVerificationStatus verify(UUID userId, RawEmailVerificationOtp rawOtp) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(rawOtp, "rawOtp");
        UserAccountEntity user = userAccountRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return EmailVerificationStatus.NOT_FOUND;
        }
        if (isAlreadyVerified(user)) {
            return EmailVerificationStatus.ALREADY_VERIFIED;
        }
        if (!isPendingVerification(user)) {
            return EmailVerificationStatus.CONFLICT;
        }
        EmailVerificationChallengeEntity challenge =
                challengeRepository.findByUserIdForUpdate(userId).orElse(null);
        if (challenge == null) {
            return EmailVerificationStatus.NO_ACTIVE_CHALLENGE;
        }
        Instant now = clock.instant();
        if (!now.isBefore(challenge.getExpiresAt())) {
            return EmailVerificationStatus.EXPIRED;
        }
        if (challenge.attemptsExhausted()) {
            return EmailVerificationStatus.ATTEMPTS_EXHAUSTED;
        }
        if (!passwordEncoder.matches(rawOtp.value(), challenge.getOtpHash())) {
            challenge.recordFailedAttempt();
            challengeRepository.saveAndFlush(challenge);
            return challenge.attemptsExhausted()
                    ? EmailVerificationStatus.ATTEMPTS_EXHAUSTED
                    : EmailVerificationStatus.INVALID_OTP;
        }

        // Step 9's separate Spring proxy uses REQUIRED and joins this transaction.
        // Its user lock is already held here; lifecycle/role/idempotency logic stays in Step 9.
        var activation = activationService.activate(new TrustedEmailActivationInput(userId, now));
        if (activation.status() != TrustedEmailActivationStatus.ACTIVATED) {
            throw new IllegalStateException("Locked pending account could not be activated");
        }
        challengeRepository.delete(challenge);
        challengeRepository.flush();
        return EmailVerificationStatus.VERIFIED;
    }

    private static boolean isAlreadyVerified(UserAccountEntity user) {
        return AccountLifecycleStatus.ACTIVE.equals(user.getAccountStatus()) && user.getEmailVerifiedAt() != null;
    }

    private static boolean isPendingVerification(UserAccountEntity user) {
        return AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION.equals(user.getAccountStatus())
                && user.getEmailVerifiedAt() == null;
    }
}
