package com.quizopia.quiz.domain.markdown;

import java.util.Arrays;
import java.util.Optional;

public enum QuizQuestionType {
    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    TRUE_FALSE_MATRIX,
    NUMERIC_FILL;

    static Optional<QuizQuestionType> fromToken(String token) {
        return Arrays.stream(values())
                .filter(value -> value.name().equals(token))
                .findFirst();
    }
}
