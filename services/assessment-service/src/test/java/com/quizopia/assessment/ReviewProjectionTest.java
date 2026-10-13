package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.AttemptViews.*;
import com.quizopia.assessment.infrastructure.ReviewProjection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewProjectionTest {
    @Test
    void closedProjectionIncludesOnlyTheOwnedFinalAnswersKeysAndExactWeights() {
        var result = new Result(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1",
                "1",
                "1",
                "100.00",
                1,
                "QUIZOPIA_GRADING_V1",
                "MANUAL_SUBMIT",
                Instant.now());
        var questions = List.of(new Question(1, "NUMERIC_FILL", "Safe stem", List.of()));
        List<Answer> answers = List.of(new Numeric("NUMERIC_FILL", "0001")),
                keys = List.of(new Numeric("NUMERIC_FILL", "0001"));
        var review = ReviewProjection.project(
                "CLOSED",
                "SUBMITTED",
                result,
                questions,
                answers,
                keys,
                List.of("1"),
                List.of("Protected explanation"));
        assertEquals(result, review.result());
        assertEquals(keys.getFirst(), review.questions().getFirst().correctAnswer());
        assertEquals("Protected explanation", review.questions().getFirst().explanationMarkdown());
        var json = SnapshotFixtures.JSON.writeValueAsString(review);
        assertTrue(json.contains("correctAnswer"));
        assertFalse(json.contains("sourceSnapshot"));
        assertThrows(
                PublicationFailure.class,
                () -> ReviewProjection.project(
                        "OPEN",
                        "SUBMITTED",
                        result,
                        questions,
                        answers,
                        keys,
                        List.of("1"),
                        List.of("Protected explanation")));
        assertThrows(
                PublicationFailure.class,
                () -> ReviewProjection.project(
                        "CLOSED",
                        "ACTIVE",
                        result,
                        questions,
                        answers,
                        keys,
                        List.of("1"),
                        List.of("Protected explanation")));
        assertThrows(
                PublicationFailure.class,
                () -> ReviewProjection.project(
                        "CLOSED",
                        "SUBMITTED",
                        null,
                        questions,
                        answers,
                        keys,
                        List.of("1"),
                        List.of("Protected explanation")));
    }
}
