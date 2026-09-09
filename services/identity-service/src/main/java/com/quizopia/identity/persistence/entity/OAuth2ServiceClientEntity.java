package com.quizopia.identity.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "oauth2_service_client",
        uniqueConstraints = @UniqueConstraint(name = "uk_oauth2_service_client_client_id", columnNames = "client_id"))
public class OAuth2ServiceClientEntity {
    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false, length = 255)
    private String clientSecretHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "oauth2_service_client_scope",
            joinColumns = @JoinColumn(name = "service_client_id"),
            uniqueConstraints =
                    @UniqueConstraint(
                            name = "pk_oauth2_service_client_scope",
                            columnNames = {"service_client_id", "scope"}))
    @Column(name = "scope", nullable = false, length = 255)
    private Set<String> scopes = new LinkedHashSet<>();

    protected OAuth2ServiceClientEntity() {}

    public OAuth2ServiceClientEntity(String clientId, String clientSecretHash, Set<String> scopes) {
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.enabled = true;
        this.scopes.addAll(scopes);
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecretHash() {
        return clientSecretHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<String> getScopes() {
        return Collections.unmodifiableSet(scopes);
    }

    @Override
    public String toString() {
        return "OAuth2ServiceClientEntity{id=" + id + ", clientId='" + clientId + "', enabled=" + enabled + ", scopes="
                + scopes + '}';
    }
}
