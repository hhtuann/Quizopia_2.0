package com.quizopia.quiz.persistence;

import com.quizopia.quiz.domain.QuizDraft;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quiz_drafts")
class QuizDraftEntity {
    @Id
    @Column(name = "quiz_id", nullable = false, updatable = false)
    private UUID quizId;

    @Column(name = "title", columnDefinition = "text")
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "authoring_source", columnDefinition = "text")
    private String authoringSource;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuizDraftEntity() {}

    QuizDraftEntity(QuizDraft draft) {
        this.quizId = draft.quizId();
        this.title = draft.title();
        this.description = draft.description();
        this.authoringSource = draft.authoringSource();
        this.updatedAt = draft.updatedAt();
    }

    QuizDraft toDomain() {
        return new QuizDraft(quizId, title, description, authoringSource, updatedAt);
    }
}
