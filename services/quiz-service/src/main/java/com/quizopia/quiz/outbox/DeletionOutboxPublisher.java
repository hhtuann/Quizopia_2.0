package com.quizopia.quiz.outbox;

import com.quizopia.quiz.domain.QuizDeletionOutboxEntry;

public interface DeletionOutboxPublisher {
    void publish(QuizDeletionOutboxEntry entry);
}
