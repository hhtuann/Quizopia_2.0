package com.quizopia.quiz.api;

public record QuizApiError(String code, String message, int status, String path) {}
