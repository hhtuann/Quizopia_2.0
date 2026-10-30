package com.quizopia.quiz.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quizopia.quiz.application.DeletionSagaObserver;
import com.quizopia.quiz.domain.QuizDeletionState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FB-015 observability contract: the saga observer emits only the fixed transition
 * counter and per-status nonterminal gauge with bounded reason vocabulary - never a
 * free-form reason, title or other content-bearing tag value.
 */
class MicrometerDeletionSagaObserverTest {
    @Test
    void emitsTransitionCounterWithBoundedReasonClasses() {
        var registry = new SimpleMeterRegistry();
        var observer = new MicrometerDeletionSagaObserver(registry);
        observer.onTransition(QuizDeletionState.ACCESS_REVOKED, null);
        observer.onTransition(QuizDeletionState.BLOCKED_BY_RETENTION, "blocked-by-retention");
        observer.onTransition(QuizDeletionState.WAITING_DEPENDENCY, "a brand new unexpected reason");
        assertEquals(
                1.0,
                registry.get(MicrometerDeletionSagaObserver.TRANSITION_METRIC)
                        .tag("to", "ACCESS_REVOKED")
                        .tag("reason", "none")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get(MicrometerDeletionSagaObserver.TRANSITION_METRIC)
                        .tag("to", "BLOCKED_BY_RETENTION")
                        .tag("reason", "blocked-by-retention")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get(MicrometerDeletionSagaObserver.TRANSITION_METRIC)
                        .tag("to", "WAITING_DEPENDENCY")
                        .tag("reason", "other")
                        .counter()
                        .count());
    }

    @Test
    void nonterminalGaugeTracksRefreshedCountsPerStatus() {
        var registry = new SimpleMeterRegistry();
        var observer = new MicrometerDeletionSagaObserver(registry);
        assertEquals(
                0.0,
                registry.get(MicrometerDeletionSagaObserver.NONTERMINAL_METRIC)
                        .tag("status", "PURGING")
                        .gauge()
                        .value());
        observer.onStatusCounts(Map.of(QuizDeletionState.PURGING, 3L, QuizDeletionState.QUIZ_FENCED, 1L));
        assertEquals(
                3.0,
                registry.get(MicrometerDeletionSagaObserver.NONTERMINAL_METRIC)
                        .tag("status", "PURGING")
                        .gauge()
                        .value());
        assertEquals(
                1.0,
                registry.get(MicrometerDeletionSagaObserver.NONTERMINAL_METRIC)
                        .tag("status", "QUIZ_FENCED")
                        .gauge()
                        .value());
        // No gauge is ever registered for terminal states.
        assertEquals(
                0L,
                registry.find(MicrometerDeletionSagaObserver.NONTERMINAL_METRIC)
                        .tag("status", "PRIMARY_PURGED")
                        .meters()
                        .size());
    }

    @Test
    void reasonClassVocabularyIsClosed() {
        assertEquals("none", DeletionSagaObserver.reasonClass(null));
        assertEquals("blocked-by-retention", DeletionSagaObserver.reasonClass("blocked-by-retention"));
        assertEquals("other", DeletionSagaObserver.reasonClass(" anything unexpected "));
    }
}
