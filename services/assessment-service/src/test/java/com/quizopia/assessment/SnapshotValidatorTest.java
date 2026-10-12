package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.infrastructure.SnapshotValidator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SnapshotValidatorTest {
    final JsonMapper json = JsonMapper.builder().build();
    final SnapshotValidator validator = new SnapshotValidator();
    final PublicationConfiguration configuration = new PublicationConfiguration(
            "Title",
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            PublicationConfiguration.Audience.PUBLIC,
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z"),
            5,
            PublicationConfiguration.MonitoringChoice.DISABLED);

    String valid() {
        var root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", 1);
        root.put("quizId", configuration.quizId());
        root.put("quizVersionId", configuration.quizVersionId());
        root.put("versionNumber", 1);
        root.put("titleSnapshot", "Title");
        root.put("descriptionSnapshot", null);
        root.put("sourceSnapshot", "Original **Markdown**\n0001\n");
        root.put("contentSchemaVersion", 1);
        root.put("createdAt", "2026-01-01T00:00:00.123456Z");
        var questions = new ArrayList<Map<String, Object>>();
        int number = 0;
        for (String type : List.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE_MATRIX", "NUMERIC_FILL")) {
            var q = new LinkedHashMap<String, Object>();
            q.put("number", ++number);
            q.put("type", type);
            q.put("stemMarkdown", "Stem\n**Markdown**");
            q.put("numericAnswer", type.equals("NUMERIC_FILL") ? "0001" : null);
            q.put("explanationMarkdown", "Protected explanation");
            q.put(
                    "options",
                    type.equals("NUMERIC_FILL")
                            ? List.of()
                            : java.util.stream.IntStream.range(0, 4)
                                    .mapToObj(i -> Map.of(
                                            "label",
                                            "" + (char) ('A' + i),
                                            "markdown",
                                            "Option " + i,
                                            "correct",
                                            i == 0))
                                    .toList());
            questions.add(q);
        }
        root.put("structuredContent", Map.of("questions", questions));
        return json.writeValueAsString(root);
    }

    String validate(String value) {
        return validator.validate(value.getBytes(StandardCharsets.UTF_8), configuration);
    }

    @Test
    void allTypesOrderAndExactNumericAndMarkdownPreserved() {
        String input = valid();
        assertEquals(json.readTree(input), json.readTree(validate(input)));
        assertTrue(validate(input).contains("0001"));
        assertTrue(validate(input).contains(".123456Z"));
        assertDoesNotThrow(() -> validate(input.replace("0001", "-.12")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0001", "2.50", "0.25", "-3.5", "-.12", "12.3", "1234"})
    void acceptedNumericSpellingAndSnapshotLocalIdentitiesStayExact(String numeric) {
        String input = new String(
                        SnapshotFixtures.snapshot(configuration, "Original **Markdown**"), StandardCharsets.UTF_8)
                .replace("0001", numeric);
        var actual = json.readTree(validate(input));
        assertEquals(json.readTree(input), actual);
        var questions = actual.path("structuredContent").path("questions");
        for (int i = 0; i < questions.size(); i++) {
            var q = questions.get(i);
            assertEquals(i + 1, q.path("number").asInt());
            assertEquals(6, q.size());
            assertFalse(q.has("id"));
            for (int j = 0; j < q.path("options").size(); j++) {
                var o = q.path("options").get(j);
                assertEquals("" + (char) ('A' + j), o.path("label").asString());
                assertEquals(3, o.size());
                assertFalse(o.has("id"));
            }
        }
        for (var option : questions.get(2).path("options"))
            assertFalse(option.path("correct").asBoolean());
        assertEquals(numeric, questions.get(3).path("numericAnswer").asString());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "schemaVersion",
                "quizId",
                "quizVersionId",
                "versionNumber",
                "descriptionSnapshot",
                "sourceSnapshot",
                "contentSchemaVersion",
                "createdAt"
            })
    void missingRequiredEvenNullableFieldDenies(String field) {
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(valid());
        root.remove(field);
        assertEquals(
                503,
                assertThrows(PublicationFailure.class, () -> validate(json.writeValueAsString(root)))
                        .status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "contentSchemaVersion", "versionNumber"})
    void overflowCannotWrapIntoSupportedVersion(String field) {
        String input = valid().replace("\"" + field + "\":1", "\"" + field + "\":4294967297");
        assertThrows(PublicationFailure.class, () -> validate(input));
    }

    @Test
    void mismatchUnknownSchemaInvalidContentAndDuplicateDeny() {
        String input = valid();
        for (String wrong : List.of(
                input.replace(
                        configuration.quizVersionId().toString(),
                        UUID.randomUUID().toString()),
                input.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                input.replace("\"number\":2", "\"number\":4"),
                input.replace("\"numericAnswer\":\"0001\"", "\"numericAnswer\":1"),
                input.replace("\"label\":\"A\"", "\"label\":\"B\""),
                input.substring(0, input.length() - 1) + ",\"schemaVersion\":1}",
                input + "{}")) assertThrows(PublicationFailure.class, () -> validate(wrong));
    }
}
