package com.quizopia.quiz.application;

public final class InvalidDeletionRequestException extends RuntimeException {
    public InvalidDeletionRequestException(String message) {
        super(message);
    }
}
