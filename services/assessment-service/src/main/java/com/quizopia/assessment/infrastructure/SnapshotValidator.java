package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.domain.PublicationConfiguration;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Validates the private v1 wire model without normalizing or discarding answer strings. */
public final class SnapshotValidator {
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private void require(boolean value) {
        if (!value) throw PublicationFailure.dependency();
    }

    private void fields(JsonNode n, String... names) {
        require(n.isObject() && n.size() == names.length);
        for (String name : names) require(n.has(name));
    }

    private void string(JsonNode n) {
        require(n.isString());
    }

    public String validate(byte[] bytes, PublicationConfiguration c) {
        return json.writeValueAsString(validatedTree(bytes, c));
    }

    public JsonNode validatedTree(byte[] bytes, PublicationConfiguration c) {
        try {
            var root = json.readTree(bytes);
            fields(
                    root,
                    "schemaVersion",
                    "quizId",
                    "quizVersionId",
                    "versionNumber",
                    "titleSnapshot",
                    "descriptionSnapshot",
                    "sourceSnapshot",
                    "structuredContent",
                    "contentSchemaVersion",
                    "createdAt");
            require(root.path("schemaVersion").isIntegralNumber()
                    && root.path("schemaVersion").canConvertToInt()
                    && root.path("schemaVersion").asInt() == 1);
            require(root.path("contentSchemaVersion").isIntegralNumber()
                    && root.path("contentSchemaVersion").canConvertToInt()
                    && root.path("contentSchemaVersion").asInt() == 1);
            string(root.path("quizId"));
            string(root.path("quizVersionId"));
            require(root.path("quizId").asString().equals(c.quizId().toString())
                    && root.path("quizVersionId")
                            .asString()
                            .equals(c.quizVersionId().toString()));
            require(root.path("versionNumber").isIntegralNumber()
                    && root.path("versionNumber").canConvertToInt()
                    && root.path("versionNumber").asInt() == c.versionNumber());
            string(root.path("titleSnapshot"));
            require(!root.path("titleSnapshot").asString().isBlank());
            require(root.path("descriptionSnapshot").isNull()
                    || root.path("descriptionSnapshot").isString());
            string(root.path("sourceSnapshot"));
            string(root.path("createdAt"));
            Instant.parse(root.path("createdAt").asString());
            var content = root.path("structuredContent");
            fields(content, "questions");
            var questions = content.path("questions");
            require(questions.isArray() && !questions.isEmpty());
            int number = 0;
            for (var q : questions) {
                fields(q, "number", "type", "stemMarkdown", "options", "numericAnswer", "explanationMarkdown");
                require(q.path("number").isIntegralNumber()
                        && q.path("number").canConvertToInt()
                        && q.path("number").asInt() == ++number);
                string(q.path("type"));
                string(q.path("stemMarkdown"));
                require(!q.path("stemMarkdown").asString().isBlank());
                require(q.path("explanationMarkdown").isNull()
                        || q.path("explanationMarkdown").isString());
                String type = q.path("type").asString();
                require(Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE_MATRIX", "NUMERIC_FILL")
                        .contains(type));
                var options = q.path("options");
                require(options.isArray());
                if (type.equals("NUMERIC_FILL")) {
                    require(options.isEmpty());
                    string(q.path("numericAnswer"));
                    String answer = q.path("numericAnswer").asString();
                    require(answer.length() == 4 && answer.matches("(?:-?[0-9]+(?:[.][0-9]+)?|-[.][0-9]+)"));
                } else {
                    require(q.path("numericAnswer").isNull() && options.size() == 4);
                    int index = 0, correct = 0;
                    for (var o : options) {
                        fields(o, "label", "markdown", "correct");
                        string(o.path("label"));
                        string(o.path("markdown"));
                        require(o.path("label").asString().equals("" + (char) ('A' + index++))
                                && !o.path("markdown").asString().isBlank()
                                && o.path("correct").isBoolean());
                        if (o.path("correct").asBoolean()) correct++;
                    }
                    require(!type.equals("SINGLE_CHOICE") || correct == 1);
                    require(!type.equals("MULTIPLE_CHOICE") || correct >= 1);
                }
            }
            return root;
        } catch (RuntimeException e) {
            throw PublicationFailure.dependency();
        }
    }
}
