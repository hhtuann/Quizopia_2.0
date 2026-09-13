package com.quizopia.classroom.api;

public record ClassroomApiError(String code, String message, int status, String path) {}
