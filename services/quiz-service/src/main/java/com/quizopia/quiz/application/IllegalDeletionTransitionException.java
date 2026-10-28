package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.Objects;

public final class IllegalDeletionTransitionException extends RuntimeException {
    public IllegalDeletionTransitionException(QuizDeletionState current, QuizDeletionState target) {
        super("Deletion state transition is not forward-only");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
    }
}
