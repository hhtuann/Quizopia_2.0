package com.quizopia.identity.application.admission;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.persistence.entity.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Complete typed snapshot. Null lifecycle is a durable SQL NULL, never an ACTIVE default. */
public record UserAuthorityFacts(
        int schemaVersion,
        UUID userId,
        boolean exists,
        String accountStatus,
        boolean emailVerified,
        List<UserRole> roles,
        Optional<Instant> revokedBefore) {
    public static final int VERSION = 1;

    public UserAuthorityFacts {
        if (schemaVersion != VERSION) throw new IllegalArgumentException("Unsupported authority schema");
        Objects.requireNonNull(userId);
        Objects.requireNonNull(roles);
        Objects.requireNonNull(revokedBefore);
        if (roles.stream().distinct().count() != roles.size()) throw new IllegalArgumentException("Duplicate roles");
        roles = roles.stream()
                .sorted(java.util.Comparator.comparing(Enum::name))
                .toList();
        if (accountStatus != null && (accountStatus.isEmpty() || accountStatus.length() > 64))
            throw new IllegalArgumentException("Invalid lifecycle value");
        if (!exists && (accountStatus != null || emailVerified || !roles.isEmpty() || revokedBefore.isPresent()))
            throw new IllegalArgumentException("Inconsistent absent account");
    }

    public boolean eligible() {
        return exists
                && AccountLifecycleStatus.ACTIVE.equals(accountStatus)
                && emailVerified
                && roles.contains(UserRole.STUDENT);
    }

    public boolean revokes(Instant issuedAt) {
        return revokedBefore.filter(cutoff -> !issuedAt.isAfter(cutoff)).isPresent();
    }
}
