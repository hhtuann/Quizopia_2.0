package com.quizopia.quiz.persistence;

import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "quiz_deletion_operations")
class QuizDeletionOperationEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "quiz_id", nullable = false, updatable = false)
    private UUID quizId;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private QuizDeletionState status;

    @Column(name = "main_progress", nullable = false)
    private int mainProgress;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_title", nullable = false, updatable = false, columnDefinition = "text")
    private String confirmedTitle;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "version_inventory", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String versionInventory;

    @Column(name = "last_reason", columnDefinition = "text")
    private String lastReason;

    protected QuizDeletionOperationEntity() {}

    QuizDeletionOperationEntity(QuizDeletionOperation operation, String versionInventory) {
        this.id = operation.id();
        this.quizId = operation.quizId();
        this.ownerUserId = operation.ownerUserId();
        this.idempotencyKey = operation.idempotencyKey();
        this.status = operation.status();
        this.mainProgress = operation.mainProgress();
        this.createdAt = operation.createdAt();
        this.updatedAt = operation.updatedAt();
        this.confirmedTitle = operation.confirmedTitle();
        this.versionInventory = versionInventory;
        this.lastReason = operation.lastReason();
    }

    void apply(QuizDeletionOperation operation) {
        this.status = operation.status();
        this.mainProgress = operation.mainProgress();
        this.updatedAt = operation.updatedAt();
        this.lastReason = operation.lastReason();
    }

    QuizDeletionOperation toDomain(DeletionJson deletionJson) {
        return new QuizDeletionOperation(
                id,
                quizId,
                ownerUserId,
                idempotencyKey,
                status,
                mainProgress,
                createdAt,
                updatedAt,
                confirmedTitle,
                deletionJson.readInventory(versionInventory),
                lastReason);
    }
}
