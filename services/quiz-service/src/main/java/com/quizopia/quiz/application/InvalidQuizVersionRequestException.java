package com.quizopia.quiz.application;

public final class InvalidQuizVersionRequestException extends RuntimeException {
    public InvalidQuizVersionRequestException(String message) {
        super(message);
    }

    public InvalidQuizVersionRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
