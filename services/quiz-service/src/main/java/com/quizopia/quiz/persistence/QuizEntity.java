package com.quizopia.quiz.persistence;

import com.quizopia.quiz.domain.Quiz;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quizzes")
class QuizEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuizEntity() {}

    QuizEntity(Quiz quiz) {
        this.id = quiz.id();
        this.ownerUserId = quiz.ownerUserId();
        this.createdAt = quiz.createdAt();
    }

    Quiz toDomain() {
        return new Quiz(id, ownerUserId, createdAt);
    }
}
