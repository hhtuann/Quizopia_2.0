package com.quizopia.identity.application.emailverification;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.activation.TrustedEmailActivationInput;
import com.quizopia.identity.application.activation.TrustedEmailActivationStatus;
import com.quizopia.identity.application.activation.TrustedEmailActivationTransaction;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationSendFence;
import com.quizopia.identity.persistence.emailverification.EmailVerificationIssuanceThrottlePersistence;
import com.quizopia.identity.persistence.emailverification.EmailVerificationOutboxPersistence;
import com.quizopia.identity.persistence.entity.EmailVerificationChallengeEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.repository.EmailVerificationChallengeRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.security.emailverification.EmailVerificationOtpGenerator;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.outbox.OutboxPayloadBinding;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class EmailVerificationTransaction {
    private static final int MAXIMUM_ISSUANCES_PER_HOUR = 5;
    private static final Duration ISSUANCE_WINDOW = Duration.ofHours(1);
    private static final String VERIFICATION_TEMPLATE_TYPE = "EMAIL_VERIFICATION_OTP";
    private static final int PAYLOAD_FORMAT_VERSION = 1;

    private final UserAccountRepository userAccountRepository;
    private final EmailVerificationChallengeRepository challengeRepository;
    private final EmailVerificationIssuanceThrottlePersistence issuanceThrottle;
    private final EmailVerificationOutboxPersistence outboxPersistence;
    private final TrustedEmailActivationTransaction activationTransaction;
    private final EmailVerificationOtpGenerator otpGenerator;
    private final OutboxPayloadCipher outboxPayloadCipher;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationTimingProtector timingProtector;
    private final EmailVerificationSendFence sendFence;
    private final Clock clock;

    public EmailVerificationTransaction(
            UserAccountRepository userAccountRepository,
            EmailVerificationChallengeRepository challengeRepository,
            EmailVerificationIssuanceThrottlePersistence issuanceThrottle,
            EmailVerificationOutboxPersistence outboxPersistence,
            TrustedEmailActivationTransaction activationTransaction,
            EmailVerificationOtpGenerator otpGenerator,
            OutboxPayloadCipher outboxPayloadCipher,
            @Qualifier("serviceClientPasswordEncoder") PasswordEncoder passwordEncoder,
            EmailVerificationTimingProtector timingProtector,
            EmailVerificationSendFence sendFence,
            @Qualifier("identityClock") Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.challengeRepository = challengeRepository;
        this.issuanceThrottle = issuanceThrottle;
        this.outboxPersistence = outboxPersistence;
        this.activationTransaction = activationTransaction;
        this.otpGenerator = otpGenerator;
        this.outboxPayloadCipher = outboxPayloadCipher;
        this.passwordEncoder = passwordEncoder;
        this.timingProtector = timingProtector;
        this.sendFence = sendFence;
        this.clock = clock;
    }

    @Transactional
    EmailVerificationIssueStatus issueChallenge(
            UUID userId, RawEmailVerificationOtp rawOtp, EmailVerificationPolicy policy) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(rawOtp, "rawOtp");
        Objects.requireNonNull(policy, "policy");
        return issueChallenge(userId, () -> rawOtp, policy);
    }

    @Transactional
    public EmailVerificationIssueStatus issueGeneratedChallenge(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return issueChallenge(userId, otpGenerator::generate, EmailVerificationPolicy.production());
    }

    private EmailVerificationIssueStatus issueChallenge(
            UUID userId, Supplier<RawEmailVerificationOtp> otpSupplier, EmailVerificationPolicy policy) {
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
        sendFence.serializeMutation(user.getEmail());
        issuanceThrottle.lockExactEmail(user.getEmail());
        var verifiedOwner =
                userAccountRepository.findVerifiedByEmail(user.getEmail()).orElse(null);
        if (verifiedOwner != null && !verifiedOwner.getId().equals(userId)) {
            outboxPersistence.terminalizeActiveForUser(userId, "EMAIL_OWNERSHIP_LOST", clock.instant());
            return EmailVerificationIssueStatus.CONFLICT;
        }
        EmailVerificationChallengeEntity challenge =
                challengeRepository.findByUserIdForUpdate(userId).orElse(null);
        Instant now = clock.instant();
        if (challenge != null && now.isBefore(challenge.getResendNotBefore())) {
            return EmailVerificationIssueStatus.COOLDOWN;
        }
        var issuanceId = issuanceThrottle.tryRecord(
                user.getEmail(), now, now.minus(ISSUANCE_WINDOW), MAXIMUM_ISSUANCES_PER_HOUR);
        if (issuanceId.isEmpty()) {
            return EmailVerificationIssueStatus.HOURLY_LIMIT;
        }

        RawEmailVerificationOtp rawOtp = Objects.requireNonNull(otpSupplier.get(), "OTP generator returned null");
        String otpHash =
                Objects.requireNonNull(passwordEncoder.encode(rawOtp.value()), "PasswordEncoder returned null");
        if (otpHash.isBlank() || otpHash.startsWith("{noop}")) {
            throw new IllegalStateException("PasswordEncoder must return an encoded OTP");
        }
        if (challenge == null) {
            challenge = new EmailVerificationChallengeEntity(user);
        }
        // PostgreSQL TIMESTAMPTZ stores microseconds. Canonicalize before both encryption and
        // persistence so a dispatcher rebuilding AAD after restart observes the same value.
        Instant otpExpiresAt = now.plus(policy.expiry()).truncatedTo(ChronoUnit.MICROS);
        UUID currentIssuanceId = issuanceId.orElseThrow();
        outboxPersistence.terminalizeActiveForUser(userId, "OTP_SUPERSEDED", now);
        challenge.replace(
                currentIssuanceId, otpHash, now, otpExpiresAt, now.plus(policy.resendCooldown()), policy.maxAttempts());
        challengeRepository.saveAndFlush(challenge);
        UUID outboxJobId = UUID.randomUUID();
        OutboxPayloadBinding binding = new OutboxPayloadBinding(
                outboxJobId, user.getEmail(), VERIFICATION_TEMPLATE_TYPE, otpExpiresAt, PAYLOAD_FORMAT_VERSION);
        var encryptedPayload = outboxPayloadCipher.encrypt(rawOtp, binding);
        outboxPersistence.enqueue(
                outboxJobId,
                currentIssuanceId,
                userId,
                user.getEmail(),
                VERIFICATION_TEMPLATE_TYPE,
                otpExpiresAt,
                now,
                encryptedPayload);
        return EmailVerificationIssueStatus.ISSUED;
    }

    @Transactional
    public EmailVerificationStatus verify(UUID userId, RawEmailVerificationOtp rawOtp) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(rawOtp, "rawOtp");
        UserAccountEntity user = userAccountRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            timingProtector.balanceConfirmation(rawOtp);
            return EmailVerificationStatus.NOT_FOUND;
        }
        if (isAlreadyVerified(user)) {
            timingProtector.balanceConfirmation(rawOtp);
            return EmailVerificationStatus.ALREADY_VERIFIED;
        }
        if (!isPendingVerification(user)) {
            timingProtector.balanceConfirmation(rawOtp);
            return EmailVerificationStatus.CONFLICT;
        }
        EmailVerificationChallengeEntity challenge =
                challengeRepository.findByUserIdForUpdate(userId).orElse(null);
        if (challenge == null) {
            timingProtector.balanceConfirmation(rawOtp);
            return EmailVerificationStatus.NO_ACTIVE_CHALLENGE;
        }
        Instant now = clock.instant();
        if (!now.isBefore(challenge.getExpiresAt())) {
            timingProtector.balanceConfirmation(rawOtp);
            return EmailVerificationStatus.EXPIRED;
        }
        if (challenge.attemptsExhausted()) {
            timingProtector.balanceConfirmation(rawOtp);
            return EmailVerificationStatus.ATTEMPTS_EXHAUSTED;
        }
        if (!passwordEncoder.matches(rawOtp.value(), challenge.getOtpHash())) {
            challenge.recordFailedAttempt();
            challengeRepository.saveAndFlush(challenge);
            return challenge.attemptsExhausted()
                    ? EmailVerificationStatus.ATTEMPTS_EXHAUSTED
                    : EmailVerificationStatus.INVALID_OTP;
        }

        // The activation transaction joins this transaction. A concurrent verified-email conflict
        // must propagate to EmailVerificationService so the complete transaction rolls back before
        // it is translated to the safe CONFLICT result.
        var activation = activationTransaction.activate(new TrustedEmailActivationInput(userId, now));
        if (activation.status() == TrustedEmailActivationStatus.CONFLICT) {
            return EmailVerificationStatus.CONFLICT;
        }
        if (activation.status() != TrustedEmailActivationStatus.ACTIVATED) {
            throw new IllegalStateException("Locked pending account could not be activated");
        }
        outboxPersistence.terminalizeActiveForUser(userId, "OTP_CONSUMED", now);
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
