package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RefreshRotationResult {
    private final RefreshRotationStatus status;
    private final UUID authenticatedUserId;
    private final RawRefreshCredential replacementCredential;
    private final Instant familyExpiresAt;
    private final Long cookieMaxAgeSeconds;

    private RefreshRotationResult(
            RefreshRotationStatus status,
            UUID authenticatedUserId,
            RawRefreshCredential replacementCredential,
            Instant familyExpiresAt,
            Long cookieMaxAgeSeconds) {
        this.status = Objects.requireNonNull(status, "status");
        this.authenticatedUserId = authenticatedUserId;
        this.replacementCredential = replacementCredential;
        this.familyExpiresAt = familyExpiresAt;
        this.cookieMaxAgeSeconds = cookieMaxAgeSeconds;
    }

    public static RefreshRotationResult success(
            UUID authenticatedUserId,
            RawRefreshCredential replacementCredential,
            Instant familyExpiresAt,
            long cookieMaxAgeSeconds) {
        if (cookieMaxAgeSeconds <= 0) {
            throw new IllegalArgumentException("cookieMaxAgeSeconds must be positive");
        }
        return new RefreshRotationResult(
                RefreshRotationStatus.SUCCESS,
                Objects.requireNonNull(authenticatedUserId),
                Objects.requireNonNull(replacementCredential),
                Objects.requireNonNull(familyExpiresAt),
                cookieMaxAgeSeconds);
    }

    public static RefreshRotationResult rejected(RefreshRotationStatus status) {
        if (status == RefreshRotationStatus.SUCCESS) {
            throw new IllegalArgumentException("A successful result requires a replacement credential");
        }
        return new RefreshRotationResult(status, null, null, null, null);
    }

    public RefreshRotationStatus status() {
        return status;
    }

    public Optional<RawRefreshCredential> replacementCredential() {
        return Optional.ofNullable(replacementCredential);
    }

    public Optional<UUID> authenticatedUserId() {
        return Optional.ofNullable(authenticatedUserId);
    }

    public Optional<Instant> familyExpiresAt() {
        return Optional.ofNullable(familyExpiresAt);
    }

    public Optional<Long> cookieMaxAgeSeconds() {
        return Optional.ofNullable(cookieMaxAgeSeconds);
    }

    @Override
    public String toString() {
        return "RefreshRotationResult{status=" + status + ", authenticatedUserIdPresent="
                + (authenticatedUserId != null) + ", replacementCredentialPresent="
                + (replacementCredential != null) + ", familyExpiresAtPresent="
                + (familyExpiresAt != null) + ", cookieMaxAgePresent=" + (cookieMaxAgeSeconds != null) + '}';
    }
}
