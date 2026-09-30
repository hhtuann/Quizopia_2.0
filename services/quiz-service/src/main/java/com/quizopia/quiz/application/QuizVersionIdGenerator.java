package com.quizopia.quiz.application;

import java.util.UUID;

@FunctionalInterface
public interface QuizVersionIdGenerator {
    UUID generate();
}
