package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizVersion;
import java.util.Objects;

public record QuizPublishResult(QuizVersion version, boolean created) {
    public QuizPublishResult {
        Objects.requireNonNull(version, "version");
    }
}
