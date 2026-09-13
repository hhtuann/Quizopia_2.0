package com.quizopia.classroom.application;

import java.util.Objects;

public record ManualStudentAdditionInput(String invitedFullName, String email) {
    public ManualStudentAdditionInput {
        invitedFullName = requireText(invitedFullName, "invitedFullName");
        email = requireText(email, "email");
    }

    @Override
    public String toString() {
        return "ManualStudentAdditionInput[invitedFullNamePresent=true, emailPresent=true]";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
