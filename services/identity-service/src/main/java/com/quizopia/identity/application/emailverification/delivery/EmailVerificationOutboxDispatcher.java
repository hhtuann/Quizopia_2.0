package com.quizopia.identity.application.emailverification.delivery;

import com.quizopia.identity.configuration.EmailOutboxDispatcherProperties;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.outbox.OutboxPayloadBinding;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.quizopia.identity.security.outbox.OutboxPayloadDecryptionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EmailVerificationOutboxDispatcher {
    static final String TEMPLATE_TYPE = "EMAIL_VERIFICATION_OTP";
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationOutboxDispatcher.class);

    private final EmailVerificationOutboxStore store;
    private final OutboxPayloadCipher payloadCipher;
    private final VerificationEmailDelivery delivery;
    private final EmailVerificationSendFence sendFence;
    private final EmailOutboxDispatcherProperties properties;
    private final Clock clock;
    private final String claimOwner;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AtomicBoolean dispatching = new AtomicBoolean();

    public EmailVerificationOutboxDispatcher(
            EmailVerificationOutboxStore store,
            OutboxPayloadCipher payloadCipher,
            VerificationEmailDelivery delivery,
            EmailVerificationSendFence sendFence,
            EmailOutboxDispatcherProperties properties,
            Clock clock,
            String claimOwner,
            ScheduledExecutorService heartbeatExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.payloadCipher = Objects.requireNonNull(payloadCipher, "payloadCipher");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.sendFence = Objects.requireNonNull(sendFence, "sendFence");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.claimOwner = requireText(claimOwner, "claimOwner");
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor");
        properties.validateEnabledConfiguration();
    }

    public int dispatchDueBatch() {
        if (!dispatching.compareAndSet(false, true)) {
            LOGGER.warn("Skipped overlapping local verification email outbox dispatch");
            return 0;
        }
        try {
            Instant now = clock.instant();
            List<ClaimedEmailVerificationOutboxJob> jobs =
                    store.claimDue(claimOwner, now, now.plus(properties.getLeaseDuration()), properties.getBatchSize());
            for (ClaimedEmailVerificationOutboxJob job : jobs) {
                dispatch(job);
            }
            return jobs.size();
        } finally {
            dispatching.set(false);
        }
    }

    private void dispatch(ClaimedEmailVerificationOutboxJob job) {
        Instant now = clock.instant();
        if (!store.renewClaim(job.id(), claimOwner, now, now.plus(properties.getLeaseDuration()))) {
            logSkippedClaim(job);
            return;
        }
        if (!now.isBefore(job.otpExpiresAt())) {
            terminalizeExpired(job, now);
            return;
        }
        if (job.attemptCount() >= properties.getMaximumAttempts()) {
            if (store.markAttemptsExhausted(job.id(), claimOwner, now)) {
                logTerminal(job, "FAILED", "MAXIMUM_ATTEMPTS_EXHAUSTED", job.attemptCount());
            } else {
                logSkippedClaim(job);
            }
            return;
        }

        var reservedAttempt = store.reserveAttempt(
                job.id(), claimOwner, now, now.plus(properties.getLeaseDuration()), properties.getMaximumAttempts());
        if (reservedAttempt.isEmpty()) {
            terminalizeObsolete(job, now);
            return;
        }
        int attemptNumber = reservedAttempt.getAsInt();

        var binding = new OutboxPayloadBinding(
                job.id(),
                job.exactRecipientEmail(),
                job.templateType(),
                job.otpExpiresAt(),
                job.encryptedPayload().payloadFormatVersion());
        final RawEmailVerificationOtp otp;
        try {
            otp = payloadCipher.decrypt(job.encryptedPayload(), binding);
        } catch (OutboxPayloadDecryptionException exception) {
            handleDecryptionFailure(job, exception.category(), now, attemptNumber);
            return;
        } catch (RuntimeException exception) {
            terminalizeFailed(job, "PAYLOAD_DECRYPTION_FAILED", now, attemptNumber);
            return;
        }
        if (!TEMPLATE_TYPE.equals(job.templateType())) {
            terminalizeFailed(job, "PAYLOAD_DECRYPTION_FAILED", now, attemptNumber);
            return;
        }
        try (ClaimHeartbeat heartbeat = startHeartbeat(job)) {
            try {
                try (EmailVerificationSendFence.SendPermit ignored =
                        sendFence.acquireForDispatch(job.exactRecipientEmail())) {
                    if (!heartbeat.ownsClaim()) {
                        logSkippedClaim(job);
                        return;
                    }
                    if (!store.isDeliverable(job.id(), claimOwner)) {
                        terminalizeObsolete(job, clock.instant());
                        return;
                    }
                    try {
                        delivery.send(new VerificationEmailMessage(
                                job.exactRecipientEmail(), job.exactUsername(), otp, job.otpExpiresAt()));
                    } catch (VerificationEmailDeliveryException exception) {
                        if (heartbeat.ownsClaim()) {
                            handleDeliveryFailure(job, exception.category(), clock.instant(), attemptNumber);
                        } else {
                            logSkippedClaim(job);
                        }
                        return;
                    } catch (RuntimeException exception) {
                        if (heartbeat.ownsClaim()) {
                            handleRetryableFailure(job, "SMTP_TRANSIENT_FAILURE", clock.instant(), attemptNumber);
                        } else {
                            logSkippedClaim(job);
                        }
                        return;
                    }
                    if (!heartbeat.ownsClaim()) {
                        logSkippedClaim(job);
                        return;
                    }
                    try {
                        if (store.markSent(job.id(), claimOwner, clock.instant())) {
                            logTerminal(job, "SENT", null, attemptNumber);
                        } else {
                            logSkippedClaim(job);
                        }
                    } catch (RuntimeException exception) {
                        LOGGER.atError()
                                .addKeyValue("jobId", job.id())
                                .addKeyValue("claimOwner", claimOwner)
                                .log("Verification email was sent but its terminal state was not persisted");
                    }
                }
            } catch (RuntimeException exception) {
                if (heartbeat.ownsClaim()) {
                    handleRetryableFailure(job, "SMTP_TRANSIENT_FAILURE", clock.instant(), attemptNumber);
                } else {
                    logSkippedClaim(job);
                }
            }
        }
    }

    private ClaimHeartbeat startHeartbeat(ClaimedEmailVerificationOutboxJob job) {
        AtomicBoolean ownsClaim = new AtomicBoolean(true);
        long periodNanos = heartbeatPeriodNanos(properties.getLeaseDuration());
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(
                () -> renewHeartbeat(job, ownsClaim), periodNanos, periodNanos, TimeUnit.NANOSECONDS);
        return new ClaimHeartbeat(ownsClaim, future);
    }

    private void renewHeartbeat(ClaimedEmailVerificationOutboxJob job, AtomicBoolean ownsClaim) {
        if (!ownsClaim.get()) {
            return;
        }
        Instant heartbeatAt = clock.instant();
        try {
            if (!store.renewClaim(job.id(), claimOwner, heartbeatAt, heartbeatAt.plus(properties.getLeaseDuration()))) {
                ownsClaim.set(false);
            }
        } catch (RuntimeException exception) {
            ownsClaim.set(false);
            LOGGER.atError()
                    .addKeyValue("jobId", job.id())
                    .addKeyValue("claimOwner", claimOwner)
                    .log("Verification email outbox lease heartbeat failed");
        }
    }

    private void handleDecryptionFailure(
            ClaimedEmailVerificationOutboxJob job,
            OutboxPayloadDecryptionException.Category category,
            Instant now,
            int attemptNumber) {
        switch (category) {
            case MISSING_KEY -> handleRetryableFailure(job, "KEY_UNAVAILABLE", now, attemptNumber);
            case AUTHENTICATION_FAILED -> terminalizeFailed(job, "PAYLOAD_AUTHENTICATION_FAILED", now, attemptNumber);
            case DECRYPTION_FAILED -> terminalizeFailed(job, "PAYLOAD_DECRYPTION_FAILED", now, attemptNumber);
        }
    }

    private void handleDeliveryFailure(
            ClaimedEmailVerificationOutboxJob job,
            VerificationEmailDeliveryException.Category category,
            Instant now,
            int attemptNumber) {
        if (category == VerificationEmailDeliveryException.Category.SMTP_PERMANENT_FAILURE) {
            terminalizeFailed(job, category.name(), now, attemptNumber);
            return;
        }
        handleRetryableFailure(job, category.name(), now, attemptNumber);
    }

    private void handleRetryableFailure(
            ClaimedEmailVerificationOutboxJob job, String failureCategory, Instant now, int attemptNumber) {
        if (attemptNumber >= properties.getMaximumAttempts()) {
            terminalizeFailed(job, failureCategory, now, attemptNumber);
            return;
        }
        Instant nextAttemptAt = now.plus(retryDelay(attemptNumber));
        if (!nextAttemptAt.isBefore(job.otpExpiresAt())) {
            if (store.markExpiredAfterAttempt(job.id(), claimOwner, now)) {
                logTerminal(job, "EXPIRED", "OTP_EXPIRED", attemptNumber);
            } else {
                logSkippedClaim(job);
            }
            return;
        }
        if (store.scheduleRetry(job.id(), claimOwner, failureCategory, nextAttemptAt)) {
            LOGGER.atWarn()
                    .addKeyValue("jobId", job.id())
                    .addKeyValue("claimOwner", claimOwner)
                    .addKeyValue("attempt", attemptNumber)
                    .addKeyValue("keyVersion", job.encryptedPayload().keyVersion())
                    .addKeyValue("state", "PENDING")
                    .addKeyValue("failureCategory", failureCategory)
                    .log("Verification email delivery scheduled for retry");
        } else {
            logSkippedClaim(job);
        }
    }

    private void terminalizeFailed(
            ClaimedEmailVerificationOutboxJob job, String failureCategory, Instant now, int attemptNumber) {
        if (store.markFailed(job.id(), claimOwner, failureCategory, now)) {
            logTerminal(job, "FAILED", failureCategory, attemptNumber);
        } else {
            logSkippedClaim(job);
        }
    }

    private void terminalizeExpired(ClaimedEmailVerificationOutboxJob job, Instant now) {
        if (store.markExpired(job.id(), claimOwner, now)) {
            logTerminal(job, "EXPIRED", "OTP_EXPIRED", job.attemptCount());
        } else {
            logSkippedClaim(job);
        }
    }

    private void terminalizeObsolete(ClaimedEmailVerificationOutboxJob job, Instant now) {
        if (store.markObsolete(job.id(), claimOwner, now)) {
            logTerminal(job, "FAILED", "OTP_OBSOLETE", job.attemptCount());
        } else {
            logSkippedClaim(job);
        }
    }

    Duration retryDelay(int failedAttemptNumber) {
        Duration delay = properties.getInitialRetryDelay();
        for (int attempt = 1; attempt < failedAttemptNumber; attempt++) {
            if (delay.compareTo(properties.getMaximumRetryDelay()) >= 0) {
                return properties.getMaximumRetryDelay();
            }
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return properties.getMaximumRetryDelay();
            }
        }
        return delay.compareTo(properties.getMaximumRetryDelay()) > 0 ? properties.getMaximumRetryDelay() : delay;
    }

    private void logTerminal(ClaimedEmailVerificationOutboxJob job, String state, String failureCategory, int attempt) {
        var event = LOGGER.atInfo()
                .addKeyValue("jobId", job.id())
                .addKeyValue("claimOwner", claimOwner)
                .addKeyValue("attempt", attempt)
                .addKeyValue("keyVersion", job.encryptedPayload().keyVersion())
                .addKeyValue("state", state);
        if (failureCategory != null) {
            event.addKeyValue("failureCategory", failureCategory);
        }
        event.log("Verification email outbox job reached terminal state");
    }

    private void logSkippedClaim(ClaimedEmailVerificationOutboxJob job) {
        LOGGER.atWarn()
                .addKeyValue("jobId", job.id())
                .addKeyValue("claimOwner", claimOwner)
                .log("Verification email outbox claim is no longer owned by this dispatcher");
    }

    private static long heartbeatPeriodNanos(Duration leaseDuration) {
        try {
            return Math.max(1L, leaseDuration.toNanos() / 3L);
        } catch (ArithmeticException exception) {
            return TimeUnit.SECONDS.toNanos(1);
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(fieldName + " must contain 1 to 128 non-blank characters");
        }
        return value;
    }

    private record ClaimHeartbeat(AtomicBoolean ownership, ScheduledFuture<?> future) implements AutoCloseable {
        boolean ownsClaim() {
            return ownership.get();
        }

        @Override
        public void close() {
            future.cancel(false);
        }
    }
}
