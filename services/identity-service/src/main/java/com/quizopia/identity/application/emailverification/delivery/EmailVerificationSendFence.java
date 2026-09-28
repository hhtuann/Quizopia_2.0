package com.quizopia.identity.application.emailverification.delivery;

/**
 * Serializes verification-email send start with mutations that supersede or consume an OTP for
 * the same exact email address.
 */
public interface EmailVerificationSendFence {
    SendPermit acquireForDispatch(String exactEmail);

    void serializeMutation(String exactEmail);

    interface SendPermit extends AutoCloseable {
        @Override
        void close();
    }
}
