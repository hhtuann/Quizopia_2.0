package com.quizopia.assessment;

import com.quizopia.assessment.domain.PublicationConfiguration;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

final class SnapshotFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static PublicationConfiguration configuration() {
        return new PublicationConfiguration(
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
    }

    static byte[] snapshot(PublicationConfiguration c, String source) {
        var root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", 1);
        root.put("quizId", c.quizId());
        root.put("quizVersionId", c.quizVersionId());
        root.put("versionNumber", c.versionNumber());
        root.put("titleSnapshot", "Title");
        root.put("descriptionSnapshot", null);
        root.put("sourceSnapshot", source);
        root.put("contentSchemaVersion", 1);
        root.put("createdAt", "2026-01-01T00:00:00.123456Z");
        var questions = new ArrayList<Map<String, Object>>();
        int number = 0;
        for (String type : List.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE_MATRIX", "NUMERIC_FILL")) {
            var q = new LinkedHashMap<String, Object>();
            q.put("number", ++number);
            q.put("type", type);
            q.put("stemMarkdown", "Câu **Markdown**\n$1+1$");
            q.put("numericAnswer", type.equals("NUMERIC_FILL") ? "0001" : null);
            q.put("explanationMarkdown", "private-answer-marker **explanation**");
            q.put(
                    "options",
                    type.equals("NUMERIC_FILL")
                            ? List.of()
                            : java.util.stream.IntStream.range(0, 4)
                                    .mapToObj(i -> Map.of(
                                            "label",
                                            "" + (char) ('A' + i),
                                            "markdown",
                                            "Lựa chọn " + i,
                                            "correct",
                                            type.equals("SINGLE_CHOICE")
                                                    ? i == 1
                                                    : type.equals("MULTIPLE_CHOICE") && (i == 0 || i == 2)))
                                    .toList());
            questions.add(q);
        }
        root.put("structuredContent", Map.of("questions", questions));
        return JSON.writeValueAsBytes(root);
    }

    static byte[] sized(PublicationConfiguration c, int bytes, boolean multibyte) {
        String prefix = multibyte ? "Tiếng Việt 🧪 " : "";
        int base = snapshot(c, prefix).length;
        byte[] result = snapshot(c, prefix + "x".repeat(bytes - base));
        if (result.length != bytes) throw new AssertionError("Fixture byte length");
        return result;
    }

    private SnapshotFixtures() {}
}
