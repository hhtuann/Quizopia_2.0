package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.domain.grading.GradingPolicy;
import com.quizopia.assessment.domain.grading.GradingPolicy.*;
import com.quizopia.assessment.persistence.JdbcAttemptFinalization;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class GradingPolicyTest {
    final GradingPolicy grader = new GradingPolicy();

    BigDecimal score(Answer key, Answer answer) {
        return grader.grade(new Snapshot(1, List.of(new Question(1, key))), List.of(answer))
                .earned();
    }

    void weight(String expected, Answer key, Answer answer) {
        assertEquals(0, new BigDecimal(expected).compareTo(score(key, answer)));
    }

    @Test
    void singleCorrectIncorrectAndUnanswered() {
        weight("1", new Single("B"), new Single("B"));
        weight("0", new Single("B"), new Single("A"));
        weight("0", new Single("B"), new Single(null));
        assertThrows(Failure.class, () -> new Single("E"));
        assertThrows(Failure.class, () -> score(new Single("B"), new Numeric("0001")));
    }

    @Test
    void exactMultipleSetNoPartialCredit() {
        var key = new Multiple(List.of("A", "C"));
        weight("1", key, new Multiple(List.of("C", "A")));
        weight("0", key, new Multiple(List.of("A")));
        weight("0", key, new Multiple(List.of("A", "B", "C")));
        weight("0", key, new Multiple(List.of()));
        assertEquals(List.of("A", "C"), new Multiple(List.of("C", "A")).labels());
        for (List<String> malformed : List.of(List.of("A", "A"), List.of("E"), Arrays.asList("A", null)))
            assertThrows(Failure.class, () -> new Multiple(malformed));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void matrixExactQuarterStepsAndAllFalse(int correct) {
        var judgments = new ArrayList<Boolean>();
        for (int i = 0; i < 4; i++) judgments.add(i < correct ? false : null);
        weight(
                BigDecimal.valueOf(correct).divide(BigDecimal.valueOf(4)).toPlainString(),
                new Matrix(List.of(false, false, false, false)),
                new Matrix(judgments));
    }

    @Test
    void mixedMatrixNullIsNotFalse() {
        weight(
                "0.5",
                new Matrix(List.of(true, false, true, false)),
                new Matrix(Arrays.asList(true, false, null, true)));
        weight("0", new Matrix(List.of(false, false, false, false)), new Matrix(List.of(true, true, true, true)));
        assertThrows(Failure.class, () -> new Matrix(List.of(true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.50", "0001", "-2.5", "-0.5", "-.25", "1234", "0000", "-0.0"})
    void acceptedNumericTokensPreserveSpelling(String token) {
        var answer = new Numeric(" \t\n\u000b\f\r" + token + "\r ");
        assertEquals(token, answer.token());
        weight("1", new Numeric(token), answer);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2.5",
                "1",
                "12345",
                "+2.5",
                "2,50",
                ".250",
                "250.",
                "1e03",
                "２.５０",
                "١٢٣٤",
                "\u00a02.50",
                "2.50\u2003",
                "2. 5",
                "-0.25"
            })
    void malformedNumericTokensAreRejected(String token) {
        assertThrows(Failure.class, () -> new Numeric(token));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\r\n\u000b\f"})
    void blankNumericIsUnanswered(String token) {
        assertNull(new Numeric(token).token());
        weight("0", new Numeric("0000"), new Numeric(token));
    }

    @Test
    void exactNumericEqualityAndInequality() {
        weight("1", new Numeric("0001"), new Numeric("1.00"));
        weight("1", new Numeric("2.50"), new Numeric("02.5"));
        weight("1", new Numeric("0000"), new Numeric("-0.0"));
        weight("0", new Numeric("2.50"), new Numeric("2.49"));
        assertEquals(0, new BigDecimal("-.25").compareTo(new BigDecimal("-0.25")));
    }

    @Test
    void completeCompositeAndUnansweredTotals() {
        var keys = List.<Answer>of(
                new Single("B"),
                new Multiple(List.of("A", "C")),
                new Matrix(List.of(false, false, false, false)),
                new Numeric("0001"));
        var questions = new ArrayList<Question>();
        for (int i = 0; i < keys.size(); i++) questions.add(new Question(i + 1, keys.get(i)));
        var snapshot = new Snapshot(1, questions);
        var score = grader.grade(
                snapshot,
                List.of(
                        new Single("B"),
                        new Multiple(List.of()),
                        new Matrix(Arrays.asList(false, false, false, null)),
                        new Numeric("1.00")));
        assertEquals("2.75", GradingPolicy.decimal(score.earned()));
        assertEquals("4", GradingPolicy.decimal(score.possible()));
        assertEquals("4", GradingPolicy.decimal(grader.grade(snapshot, keys).earned()));
        assertEquals(
                "0",
                GradingPolicy.decimal(grader.grade(
                                snapshot,
                                keys.stream()
                                        .map(a -> GradingPolicy.unanswered(a.type()))
                                        .toList())
                        .earned()));
        assertFalse(snapshot.toString().contains("0001"));
        assertThrows(Failure.class, () -> new Snapshot(2, questions));
        assertThrows(Failure.class, () -> new Snapshot(1, List.of(new Question(2, new Single("A")))));
    }

    @ParameterizedTest
    @CsvSource({"2.75,4,68.75", "1,3,33.33", "2,3,66.67", "0,4,0.00", "4,4,100.00", "1,32,3.13"})
    void presentationOnlyHalfUp(String earned, String possible, String display) {
        assertEquals(display, GradingPolicy.percentage(new BigDecimal(earned), new BigDecimal(possible)));
    }

    @Test
    void decimalFormattingAndDeadlineMicrosecondBoundary() {
        assertEquals("0", GradingPolicy.decimal(new BigDecimal("-0.000")));
        assertEquals("1000", GradingPolicy.decimal(new BigDecimal("1E+3")));
        var t = Instant.parse("2026-01-01T00:00:00Z");
        assertFalse(JdbcAttemptFinalization.due(t, t.minusNanos(1000)));
        assertTrue(JdbcAttemptFinalization.due(t, t));
        assertTrue(JdbcAttemptFinalization.due(t, t.plusNanos(1000)));
        assertFalse(JdbcAttemptFinalization.due(null, t));
    }
}
