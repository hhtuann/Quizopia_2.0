package com.quizopia.identity.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_access_revocation")
public class UserAccessRevocationEntity {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "revoked_before", nullable = false)
    private Instant revokedBefore;

    protected UserAccessRevocationEntity() {}

    public UserAccessRevocationEntity(UUID userId, Instant revokedBefore) {
        this.userId = userId;
        this.revokedBefore = revokedBefore;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getRevokedBefore() {
        return revokedBefore;
    }
}
