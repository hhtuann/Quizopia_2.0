package com.quizopia.assessment.domain.attempt;

import java.time.Instant;

/** Stored acknowledgment bytes are restricted internal data, not an API serializer. */
public record OperationReceipt(
        String action,
        String requestDigest,
        long acceptedRevision,
        Instant acknowledgedAt,
        int responseStatus,
        String responseJson) {
    @Override
    public String toString() {
        return "OperationReceipt[redacted]";
    }
}
