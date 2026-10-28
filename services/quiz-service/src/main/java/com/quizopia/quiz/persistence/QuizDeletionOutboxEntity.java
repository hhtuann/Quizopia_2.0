package com.quizopia.quiz.persistence;

import com.quizopia.quiz.domain.QuizDeletionOutboxEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "quiz_deletion_outbox")
class QuizDeletionOutboxEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    protected QuizDeletionOutboxEntity() {}

    QuizDeletionOutboxEntity(QuizDeletionOutboxEntry entry, String payload) {
        this.id = entry.id();
        this.operationId = entry.operationId();
        this.kind = entry.kind();
        this.payload = payload;
        this.createdAt = entry.createdAt();
        this.dispatchedAt = entry.dispatchedAt();
        this.attempts = entry.attempts();
        this.nextAttemptAt = entry.nextAttemptAt();
        this.lastError = entry.lastError();
    }

    QuizDeletionOutboxEntry toDomain(DeletionJson deletionJson) {
        DeletionJson.OutboxPayload parsed = deletionJson.readPayload(payload);
        return new QuizDeletionOutboxEntry(
                id,
                parsed.operationId(),
                parsed.quizId(),
                parsed.ownerUserId(),
                kind,
                parsed.versionInventory(),
                createdAt,
                nextAttemptAt,
                attempts,
                dispatchedAt,
                lastError);
    }
}
