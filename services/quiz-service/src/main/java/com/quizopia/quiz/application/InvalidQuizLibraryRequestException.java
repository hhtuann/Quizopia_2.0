package com.quizopia.quiz.application;

public final class InvalidQuizLibraryRequestException extends RuntimeException {
    public InvalidQuizLibraryRequestException(String message) {
        super(message);
    }

    public InvalidQuizLibraryRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
