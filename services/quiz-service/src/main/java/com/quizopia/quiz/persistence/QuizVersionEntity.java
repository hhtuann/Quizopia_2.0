package com.quizopia.quiz.persistence;

import com.quizopia.quiz.domain.QuizVersion;
import com.quizopia.quiz.domain.markdown.QuizContent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "quiz_versions",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_quiz_versions_quiz_version",
                        columnNames = {"quiz_id", "version_number"}))
class QuizVersionEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "quiz_id", nullable = false, updatable = false)
    private UUID quizId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "title_snapshot", columnDefinition = "text", updatable = false)
    private String titleSnapshot;

    @Column(name = "description_snapshot", columnDefinition = "text", updatable = false)
    private String descriptionSnapshot;

    @Column(name = "source_snapshot", nullable = false, columnDefinition = "text", updatable = false)
    private String sourceSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_content", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String structuredContent;

    @Column(name = "content_schema_version", nullable = false, updatable = false)
    private int contentSchemaVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuizVersionEntity() {}

    QuizVersionEntity(QuizVersion version, String structuredContent) {
        this.id = version.id();
        this.quizId = version.quizId();
        this.versionNumber = version.versionNumber();
        this.titleSnapshot = version.titleSnapshot();
        this.descriptionSnapshot = version.descriptionSnapshot();
        this.sourceSnapshot = version.sourceSnapshot();
        this.structuredContent = structuredContent;
        this.contentSchemaVersion = version.contentSchemaVersion();
        this.createdAt = version.createdAt();
    }

    String structuredContent() {
        return structuredContent;
    }

    QuizVersion toDomain(QuizContent content) {
        return new QuizVersion(
                id,
                quizId,
                versionNumber,
                titleSnapshot,
                descriptionSnapshot,
                sourceSnapshot,
                content,
                contentSchemaVersion,
                createdAt);
    }
}
