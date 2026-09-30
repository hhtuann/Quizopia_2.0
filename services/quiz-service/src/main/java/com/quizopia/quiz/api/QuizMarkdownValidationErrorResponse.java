package com.quizopia.quiz.api;

import java.util.List;

public record QuizMarkdownValidationErrorResponse(
        String code, String message, int status, String path, String traceId, List<QuizMarkdownErrorResponse> errors) {}
