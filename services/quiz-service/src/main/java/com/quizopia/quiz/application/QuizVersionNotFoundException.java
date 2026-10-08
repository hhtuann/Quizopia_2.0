package com.quizopia.quiz.application;

import java.util.UUID;

public final class QuizVersionNotFoundException extends RuntimeException {
    public QuizVersionNotFoundException(UUID quizId, int versionNumber) {
        super("Quiz version " + versionNumber + " was not found for quiz " + quizId);
    }
}
