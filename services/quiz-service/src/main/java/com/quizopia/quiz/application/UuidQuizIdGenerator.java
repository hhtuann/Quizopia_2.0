package com.quizopia.quiz.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidQuizIdGenerator implements QuizIdGenerator {
    @Override
    public UUID generate() {
        return UUID.randomUUID();
    }
}
