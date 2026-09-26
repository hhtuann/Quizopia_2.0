package com.quizopia.identity.application.refresh;

public enum RefreshRotationStatus {
    SUCCESS,
    UNKNOWN_CREDENTIAL,
    EXPIRED_FAMILY,
    REVOKED_FAMILY,
    REUSE_DETECTED
}
