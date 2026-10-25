package com.quizopia.classroom.application;

public final class ClassroomDirectoryUnavailableException extends RuntimeException {
    public ClassroomDirectoryUnavailableException() {
        super("Directory is unavailable");
    }
}
