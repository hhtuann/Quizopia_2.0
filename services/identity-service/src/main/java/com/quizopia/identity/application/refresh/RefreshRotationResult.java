package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.util.Objects;
import java.util.Optional;

public final class RefreshRotationResult {
    private final RefreshRotationStatus status;
    private final RawRefreshCredential replacementCredential;

    private RefreshRotationResult(RefreshRotationStatus status, RawRefreshCredential replacementCredential) {
        this.status = Objects.requireNonNull(status, "status");
        this.replacementCredential = replacementCredential;
    }

    public static RefreshRotationResult success(RawRefreshCredential replacementCredential) {
        return new RefreshRotationResult(RefreshRotationStatus.SUCCESS, Objects.requireNonNull(replacementCredential));
    }

    public static RefreshRotationResult rejected(RefreshRotationStatus status) {
        if (status == RefreshRotationStatus.SUCCESS) {
            throw new IllegalArgumentException("A successful result requires a replacement credential");
        }
        return new RefreshRotationResult(status, null);
    }

    public RefreshRotationStatus status() {
        return status;
    }

    public Optional<RawRefreshCredential> replacementCredential() {
        return Optional.ofNullable(replacementCredential);
    }

    @Override
    public String toString() {
        return "RefreshRotationResult{status=" + status + ", replacementCredentialPresent="
                + (replacementCredential != null) + '}';
    }
}
