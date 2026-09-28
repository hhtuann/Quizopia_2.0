package com.quizopia.identity.application.emailverification.delivery;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

public interface EmailVerificationOutboxStore {
    List<ClaimedEmailVerificationOutboxJob> claimDue(
            String claimOwner, Instant now, Instant claimExpiresAt, int batchSize);

    boolean renewClaim(UUID jobId, String claimOwner, Instant now, Instant claimExpiresAt);

    OptionalInt reserveAttempt(UUID jobId, String claimOwner, Instant now, Instant claimExpiresAt, int maximumAttempts);

    boolean isDeliverable(UUID jobId, String claimOwner);

    boolean markSent(UUID jobId, String claimOwner, Instant sentAt);

    boolean scheduleRetry(UUID jobId, String claimOwner, String failureCategory, Instant nextAttemptAt);

    boolean markFailed(UUID jobId, String claimOwner, String failureCategory, Instant terminalAt);

    boolean markAttemptsExhausted(UUID jobId, String claimOwner, Instant terminalAt);

    boolean markExpired(UUID jobId, String claimOwner, Instant terminalAt);

    boolean markExpiredAfterAttempt(UUID jobId, String claimOwner, Instant terminalAt);

    boolean markObsolete(UUID jobId, String claimOwner, Instant terminalAt);
}
