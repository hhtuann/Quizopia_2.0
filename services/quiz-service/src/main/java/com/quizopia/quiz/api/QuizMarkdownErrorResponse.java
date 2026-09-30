package com.quizopia.quiz.api;

import com.quizopia.quiz.domain.markdown.QuizMarkdownError;

public record QuizMarkdownErrorResponse(
        String code, Integer questionNumber, Integer line, Integer column, String message) {
    static QuizMarkdownErrorResponse from(QuizMarkdownError error) {
        return new QuizMarkdownErrorResponse(
                error.code(), error.questionNumber(), error.line(), error.column(), error.message());
    }
}
