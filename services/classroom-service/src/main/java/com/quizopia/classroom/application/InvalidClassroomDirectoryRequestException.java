package com.quizopia.classroom.application;

public final class InvalidClassroomDirectoryRequestException extends RuntimeException {
    public InvalidClassroomDirectoryRequestException() {
        super("Request is invalid");
    }
}
