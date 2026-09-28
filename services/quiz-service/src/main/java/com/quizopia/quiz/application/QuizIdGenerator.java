package com.quizopia.quiz.application;

import java.util.UUID;

/** Generates stable quiz identifiers owned by Quiz Service. */
@FunctionalInterface
public interface QuizIdGenerator {
    UUID generate();
}
