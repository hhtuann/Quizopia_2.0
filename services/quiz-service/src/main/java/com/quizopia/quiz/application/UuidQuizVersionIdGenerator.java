package com.quizopia.quiz.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class UuidQuizVersionIdGenerator implements QuizVersionIdGenerator {
    @Override
    public UUID generate() {
        return UUID.randomUUID();
    }
}
