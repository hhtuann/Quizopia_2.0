package com.quizopia.identity.application.googlelink;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record GoogleIdentityLinkResult(GoogleIdentityLinkStatus status, Optional<UUID> userId) {
    public GoogleIdentityLinkResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(userId, "userId");
        boolean requiresUser = status == GoogleIdentityLinkStatus.ALREADY_LINKED
                || status == GoogleIdentityLinkStatus.LINKED_EXISTING_USER;
        if (requiresUser != userId.isPresent()) {
            throw new IllegalArgumentException("userId presence does not match linking status");
        }
    }

    public static GoogleIdentityLinkResult alreadyLinked(UUID userId) {
        return new GoogleIdentityLinkResult(GoogleIdentityLinkStatus.ALREADY_LINKED, Optional.of(userId));
    }

    public static GoogleIdentityLinkResult linkedExistingUser(UUID userId) {
        return new GoogleIdentityLinkResult(GoogleIdentityLinkStatus.LINKED_EXISTING_USER, Optional.of(userId));
    }

    public static GoogleIdentityLinkResult noSafeMatch() {
        return new GoogleIdentityLinkResult(GoogleIdentityLinkStatus.NO_SAFE_MATCH, Optional.empty());
    }

    public static GoogleIdentityLinkResult conflict() {
        return new GoogleIdentityLinkResult(GoogleIdentityLinkStatus.CONFLICT, Optional.empty());
    }
}
