package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDraft;
import java.util.Objects;

/** Stable quiz identity together with its current mutable draft. */
public record QuizDraftDetails(Quiz quiz, QuizDraft draft) {
    public QuizDraftDetails {
        Objects.requireNonNull(quiz, "quiz");
        Objects.requireNonNull(draft, "draft");
        if (!quiz.id().equals(draft.quizId())) {
            throw new IllegalArgumentException("Quiz and draft identifiers must match");
        }
    }
}
