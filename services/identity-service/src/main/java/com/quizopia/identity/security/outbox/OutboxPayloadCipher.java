package com.quizopia.identity.security.outbox;

import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;

public interface OutboxPayloadCipher {
    EncryptedOutboxPayload encrypt(RawEmailVerificationOtp rawOtp, OutboxPayloadBinding binding);

    RawEmailVerificationOtp decrypt(EncryptedOutboxPayload payload, OutboxPayloadBinding binding);
}
