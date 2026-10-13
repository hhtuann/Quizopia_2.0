package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.domain.grading.GradingPolicy;
import com.quizopia.assessment.domain.grading.GradingPolicy.*;
import com.quizopia.assessment.infrastructure.GradingDocuments;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.*;

class GradingDocumentsTest {
    final GradingDocuments documents = new GradingDocuments();
    final com.quizopia.assessment.domain.PublicationConfiguration config = SnapshotFixtures.configuration();

    Snapshot snapshot() {
        return documents.snapshot(SnapshotFixtures.snapshot(config, "protected source"), config, 1, 1);
    }

    static String replacement() {
        return """
        [{"questionNumber":4,"answer":{"type":"NUMERIC_FILL","value":" 1.00 "}},
         {"questionNumber":2,"answer":{"type":"MULTIPLE_CHOICE","optionLabels":["C","A"]}},
         {"questionNumber":1,"answer":{"type":"SINGLE_CHOICE","optionLabel":"B"}},
         {"questionNumber":3,"answer":{"type":"TRUE_FALSE_MATRIX","judgments":{"D":null,"C":false,"B":false,"A":false}}}]
        """;
    }

    @Test
    void stableCompleteCanonicalizationAndDigestSpelling() {
        var answers = documents.replacement(replacement(), snapshot());
        String canonical = documents.canonical(answers);
        assertEquals(canonical, documents.canonical(documents.replacement(canonical, snapshot())));
        assertTrue(canonical.startsWith(
                "[{\"answer\":{\"optionLabel\":\"B\",\"type\":\"SINGLE_CHOICE\"},\"questionNumber\":1}"));
        assertTrue(canonical.contains("\"optionLabels\":[\"A\",\"C\"]"));
        assertTrue(canonical.contains("\"value\":\"1.00\""));
        assertNotEquals(
                canonical,
                documents.canonical(documents.replacement(replacement().replace("1.00", "0001"), snapshot())));
        assertEquals(
                "3.75",
                GradingPolicy.decimal(
                        new GradingPolicy().grade(snapshot(), answers).earned()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "null", "[{}]", "[{\"questionNumber\":1,\"answer\":{}}]"})
    void incompleteOrMalformedReplacementRejected(String value) {
        assertThrows(Failure.class, () -> documents.replacement(value, snapshot()));
    }

    @Test
    void malformedTypedAnswersAndDuplicateKeysOrQuestionsRejected() {
        var base = documents.tree(replacement());
        for (String bad : List.of(
                "{\"type\":\"NUMERIC_FILL\",\"value\":2.5}",
                "{\"type\":\"NUMERIC_FILL\",\"value\":\"2.5\"}",
                "{\"type\":\"NUMERIC_FILL\",\"value\":null,\"correct\":true}",
                "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"B\"}")) {
            var copy = base.deepCopy();
            ((ObjectNode) copy.get(0)).set("answer", documents.tree(bad));
            assertThrows(Failure.class, () -> documents.replacement(copy.toString(), snapshot()));
        }
        assertThrows(
                Failure.class,
                () -> documents.replacement(
                        replacement().replace("\"questionNumber\":4", "\"questionNumber\":1"), snapshot()));
        assertThrows(
                Failure.class,
                () -> documents.replacement(
                        replacement().replace("\"questionNumber\":4", "\"questionNumber\":5"), snapshot()));
        assertThrows(
                Failure.class,
                () -> documents.replacement(
                        replacement().replace("\"value\":\" 1.00 \"", "\"value\":null,\"value\":\"0001\""),
                        snapshot()));
        assertThrows(Failure.class, () -> documents.replacement(replacement() + "[]", snapshot()));
        var q = snapshot().questions();
        for (String bad : List.of(
                "{\"type\":\"MULTIPLE_CHOICE\",\"optionLabels\":[\"A\",\"A\"]}",
                "{\"type\":\"MULTIPLE_CHOICE\",\"optionLabels\":[null]}"))
            assertThrows(Failure.class, () -> documents.answer(documents.tree(bad), q.get(1)));
        for (String bad : List.of(
                "{\"type\":\"TRUE_FALSE_MATRIX\",\"judgments\":{\"A\":false,\"B\":false,\"C\":false}}",
                "{\"type\":\"TRUE_FALSE_MATRIX\",\"judgments\":{\"A\":\"false\",\"B\":false,\"C\":null,\"D\":null}}"))
            assertThrows(Failure.class, () -> documents.answer(documents.tree(bad), q.get(2)));
    }

    @Test
    void corruptSnapshotFailsSanitizedWithoutFallback() {
        String raw = new String(
                SnapshotFixtures.snapshot(config, "protected source"), java.nio.charset.StandardCharsets.UTF_8);
        for (String broken : List.of(
                raw.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                raw.replace("\"contentSchemaVersion\":1", "\"contentSchemaVersion\":2"),
                raw.replace("\"number\":1", "\"number\":2"),
                raw.replace("\"0001\"", "\"secret-answer\""),
                raw.replace("\"B\"", "\"E\""),
                raw + "{}")) {
            var failure = assertThrows(
                    Failure.class,
                    () -> documents.snapshot(broken.getBytes(java.nio.charset.StandardCharsets.UTF_8), config, 1, 1));
            assertEquals("GRADING_UNAVAILABLE", failure.getMessage());
            assertNull(failure.getCause());
        }
        assertThrows(Failure.class, () -> documents.snapshot(raw.getBytes(), config, 2, 1));
        assertThrows(Failure.class, () -> documents.snapshot(raw.getBytes(), config, 1, 2));
    }

    @Test
    void largeSnapshotRemainsCompleteWithoutQuestionCountCap() {
        var root = (ObjectNode) documents.tree(new String(SnapshotFixtures.snapshot(config, "large source")));
        var questions = (ArrayNode) root.path("structuredContent").path("questions");
        var single = questions.get(0).deepCopy();
        questions.removeAll();
        for (int i = 1; i <= 2048; i++) {
            var q = (ObjectNode) single.deepCopy();
            q.put("number", i);
            questions.add(q);
        }
        var snapshot =
                documents.snapshot(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), config, 1, 1);
        var answers = Collections.<Answer>nCopies(2048, new Single("B"));
        assertEquals(
                "2048",
                GradingPolicy.decimal(
                        new GradingPolicy().grade(snapshot, answers).earned()));
        assertEquals(2048, documents.tree(documents.canonical(answers)).size());
    }
}
