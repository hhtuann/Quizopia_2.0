package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.markdown.QuizMarkdownError;
import java.util.List;
import java.util.Objects;

public final class QuizMarkdownInvalidException extends RuntimeException {
    private final List<QuizMarkdownError> errors;

    public QuizMarkdownInvalidException(List<QuizMarkdownError> errors) {
        super("Quiz Markdown validation failed.");
        this.errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (this.errors.isEmpty()) {
            throw new IllegalArgumentException("errors must not be empty");
        }
    }

    public List<QuizMarkdownError> errors() {
        return errors;
    }
}
