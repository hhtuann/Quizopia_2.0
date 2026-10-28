package com.quizopia.quiz.outbox;

import com.quizopia.quiz.application.QuizDeletionOutboxRepository;
import com.quizopia.quiz.domain.QuizDeletionOutboxEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class DeletionOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DeletionOutboxDispatcher.class);
    private static final int BATCH_SIZE = 20;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);

    private final QuizDeletionOutboxRepository outboxRepository;
    private final DeletionOutboxPublisher publisher;
    private final Clock clock;

    public DeletionOutboxDispatcher(
            QuizDeletionOutboxRepository outboxRepository, DeletionOutboxPublisher publisher, Clock clock) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 2000)
    public void dispatchDue() {
        try {
            dispatchOnce();
        } catch (RuntimeException exception) {
            log.warn("Deletion outbox dispatch round failed", exception);
        }
    }

    public void dispatchOnce() {
        List<QuizDeletionOutboxEntry> due;
        try {
            due = outboxRepository.findDue(clock.instant(), BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.warn("Deletion outbox due lookup failed", exception);
            return;
        }
        for (QuizDeletionOutboxEntry entry : due) {
            try {
                publisher.publish(entry);
                outboxRepository.markDispatched(entry.id(), clock.instant());
            } catch (RuntimeException exception) {
                Instant failedAt = clock.instant();
                outboxRepository.recordAttempt(
                        entry.id(),
                        failedAt,
                        failedAt.plus(BASE_BACKOFF.multipliedBy(1L << Math.min(entry.attempts(), 16))),
                        exception.getClass().getName());
            }
        }
    }
}
