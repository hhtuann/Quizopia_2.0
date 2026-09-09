package com.quizopia.identity.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "refresh_token",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_refresh_token_token_hash", columnNames = "token_hash"),
            @UniqueConstraint(
                    name = "uk_refresh_token_id_family",
                    columnNames = {"id", "family_id"})
        })
public class RefreshTokenEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false)
    private RefreshTokenFamilyEntity family;

    @Column(name = "token_hash", nullable = false, columnDefinition = "bytea")
    private byte[] tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    protected RefreshTokenEntity() {}

    public RefreshTokenEntity(RefreshTokenFamilyEntity family, byte[] tokenHash) {
        this.family = family;
        this.tokenHash = tokenHash.clone();
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public RefreshTokenFamilyEntity getFamily() {
        return family;
    }

    public byte[] getTokenHash() {
        return tokenHash.clone();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public UUID getReplacedByTokenId() {
        return replacedByTokenId;
    }

    public void setReplacedByTokenId(UUID replacedByTokenId) {
        this.replacedByTokenId = replacedByTokenId;
    }
}
