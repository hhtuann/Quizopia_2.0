package com.quizopia.classroom.domain;

import java.util.Objects;

final class RequiredText {
    private RequiredText() {}

    static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
