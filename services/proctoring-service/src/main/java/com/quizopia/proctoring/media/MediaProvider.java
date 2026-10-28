package com.quizopia.proctoring.media;

import java.time.Instant;

public interface MediaProvider {
    boolean qualified();

    void ensureRoom(String room);

    String token(String room, String identity, String role, Instant expiresAt);

    void revoke(String room, String identity, Instant cutoff);

    void deleteRoom(String room);

    /** Stable provider identity for erasure bookkeeping; never a credential. */
    default String providerName() {
        return "unspecified";
    }

    /**
     * FB-015 PR C: quiz erasure request status. No provider erasure API exists yet, so the
     * only honest answer is PENDING_PROVIDER; a real implementation must replace this and
     * surface verifiable receipts before any ALL_COPIES_ERASED claim.
     */
    default String requestQuizErasure() {
        return "PENDING_PROVIDER";
    }
}
