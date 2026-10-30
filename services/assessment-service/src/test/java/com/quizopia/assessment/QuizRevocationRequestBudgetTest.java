package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.configuration.AssessmentDeletionProperties;
import com.quizopia.assessment.security.deletion.QuizRevocationRequestBudget;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

/** Deterministic rolling-minute budget: fixed memory, exact caller only, no reset by rotation. */
class QuizRevocationRequestBudgetTest {
    AtomicLong nanos = new AtomicLong();
    QuizRevocationRequestBudget budget = new QuizRevocationRequestBudget(
            new AssessmentDeletionProperties(true, "quiz-service", false, 8, 2, 10, 20, 500), nanos::get);

    @Test
    void admitsWithinWindowAndBoundsBurstsAfterwards() {
        budget.acquire("quiz-service");
        budget.acquire("quiz-service");
        var limited = assertThrows(PublicationFailure.class, () -> budget.acquire("quiz-service"));
        assertEquals(429, limited.status());
        assertEquals("REVOCATION_RATE_LIMITED", limited.code());
        // Rotation cannot reset the budget.
        var forbidden = assertThrows(PublicationFailure.class, () -> budget.acquire("other-service"));
        assertEquals(403, forbidden.status());
        nanos.addAndGet(java.time.Duration.ofSeconds(61).toNanos());
        budget.acquire("quiz-service");
    }
}
