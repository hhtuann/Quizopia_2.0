package com.quizopia.identity.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "role_grant_audit",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_role_grant_audit_first_grant",
                        columnNames = {"target_user_id", "role", "action"}))
public class RoleGrantAuditEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private UserAccountEntity actor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_user_id", nullable = false)
    private UserAccountEntity target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoleGrantAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoleGrantAuditSource source;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected RoleGrantAuditEntity() {}

    public RoleGrantAuditEntity(
            UserAccountEntity actor,
            UserAccountEntity target,
            UserRole role,
            RoleGrantAuditAction action,
            RoleGrantAuditSource source,
            Instant occurredAt) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.target = Objects.requireNonNull(target, "target");
        this.role = Objects.requireNonNull(role, "role");
        this.action = Objects.requireNonNull(action, "action");
        this.source = Objects.requireNonNull(source, "source");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public UserAccountEntity getActor() {
        return actor;
    }

    public UserAccountEntity getTarget() {
        return target;
    }

    public UserRole getRole() {
        return role;
    }

    public RoleGrantAuditAction getAction() {
        return action;
    }

    public RoleGrantAuditSource getSource() {
        return source;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
