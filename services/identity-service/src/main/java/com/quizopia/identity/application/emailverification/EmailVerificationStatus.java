package com.quizopia.identity.application.emailverification;

public enum EmailVerificationStatus {
    VERIFIED,
    INVALID_OTP,
    EXPIRED,
    ATTEMPTS_EXHAUSTED,
    NO_ACTIVE_CHALLENGE,
    ALREADY_VERIFIED,
    NOT_FOUND,
    CONFLICT
}
