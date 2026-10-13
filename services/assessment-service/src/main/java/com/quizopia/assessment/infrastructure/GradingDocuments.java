package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.domain.grading.GradingPolicy;
import com.quizopia.assessment.domain.grading.GradingPolicy.*;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Strict private snapshot/answer adapter. No input body or parser cause is exposed in failures. */
public final class GradingDocuments {
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public Snapshot snapshot(byte[] bytes, PublicationConfiguration configuration, int schema, int policy) {
        try {
            if (schema != 1 || policy != 1) throw GradingPolicy.unavailable();
            var root = new SnapshotValidator().validatedTree(bytes, configuration);
            var questions = new ArrayList<Question>();
            for (var q : root.path("structuredContent").path("questions")) {
                Type type = Type.valueOf(q.path("type").asString());
                var correct = new ArrayList<String>();
                var truths = new ArrayList<Boolean>();
                for (var option : q.path("options")) {
                    boolean truth = option.path("correct").asBoolean();
                    truths.add(truth);
                    if (truth) correct.add(option.path("label").asString());
                }
                Answer key =
                        switch (type) {
                            case SINGLE_CHOICE -> new Single(correct.getFirst());
                            case MULTIPLE_CHOICE -> new Multiple(correct);
                            case TRUE_FALSE_MATRIX -> new Matrix(truths);
                            case NUMERIC_FILL ->
                                new Numeric(q.path("numericAnswer").asString());
                        };
                questions.add(new Question(q.path("number").asInt(), key));
            }
            return new Snapshot(policy, questions);
        } catch (RuntimeException e) {
            throw GradingPolicy.unavailable();
        }
    }

    private static void fields(JsonNode n, String... fields) {
        if (!n.isObject() || n.size() != fields.length) throw GradingPolicy.invalid();
        for (String f : fields) if (!n.has(f)) throw GradingPolicy.invalid();
    }

    private static String textOrNull(JsonNode n) {
        if (n.isNull()) return null;
        if (!n.isString()) throw GradingPolicy.invalid();
        return n.asString();
    }

    public Answer answer(JsonNode node, Question question) {
        try {
            if (!node.path("type").isString()
                    || !node.path("type")
                            .asString()
                            .equals(question.key().type().name())) throw GradingPolicy.invalid();
            return switch (question.key().type()) {
                case SINGLE_CHOICE -> {
                    fields(node, "type", "optionLabel");
                    yield new Single(textOrNull(node.path("optionLabel")));
                }
                case MULTIPLE_CHOICE -> {
                    fields(node, "type", "optionLabels");
                    if (!node.path("optionLabels").isArray()) throw GradingPolicy.invalid();
                    var labels = new ArrayList<String>();
                    for (var label : node.path("optionLabels")) {
                        if (!label.isString()) throw GradingPolicy.invalid();
                        labels.add(label.asString());
                    }
                    yield new Multiple(labels);
                }
                case TRUE_FALSE_MATRIX -> {
                    fields(node, "type", "judgments");
                    var judgments = node.path("judgments");
                    fields(judgments, "A", "B", "C", "D");
                    var values = new ArrayList<Boolean>();
                    for (String label : List.of("A", "B", "C", "D")) {
                        var value = judgments.path(label);
                        if (!value.isNull() && !value.isBoolean()) throw GradingPolicy.invalid();
                        values.add(value.isNull() ? null : value.asBoolean());
                    }
                    yield new Matrix(values);
                }
                case NUMERIC_FILL -> {
                    fields(node, "type", "value");
                    yield new Numeric(textOrNull(node.path("value")));
                }
            };
        } catch (RuntimeException e) {
            throw GradingPolicy.invalid();
        }
    }

    public List<Answer> replacement(String document, Snapshot snapshot) {
        try {
            JsonNode root = json.readTree(document);
            if (!root.isArray() || root.size() != snapshot.questions().size()) throw GradingPolicy.invalid();
            Answer[] answers = new Answer[root.size()];
            for (var entry : root) {
                fields(entry, "questionNumber", "answer");
                var number = entry.path("questionNumber");
                if (!number.isIntegralNumber() || !number.canConvertToInt()) throw GradingPolicy.invalid();
                int n = number.asInt();
                if (n < 1 || n > answers.length || answers[n - 1] != null) throw GradingPolicy.invalid();
                answers[n - 1] =
                        answer(entry.path("answer"), snapshot.questions().get(n - 1));
            }
            return List.of(answers);
        } catch (RuntimeException e) {
            throw GradingPolicy.invalid();
        }
    }

    public JsonNode tree(String value) {
        return json.readTree(value);
    }

    public String answerJson(Answer answer) {
        return json.writeValueAsString(answerValue(answer));
    }

    private Map<String, Object> answerValue(Answer answer) {
        var value = new TreeMap<String, Object>();
        value.put("type", answer.type().name());
        switch (answer) {
            case Single s -> value.put("optionLabel", s.label());
            case Multiple m -> value.put("optionLabels", m.labels());
            case Matrix m -> {
                var judgments = new TreeMap<String, Object>();
                for (int i = 0; i < 4; i++)
                    judgments.put("" + (char) ('A' + i), m.judgments().get(i));
                value.put("judgments", judgments);
            }
            case Numeric n -> value.put("value", n.token());
        }
        return value;
    }

    public String canonical(List<Answer> answers) {
        var entries = new ArrayList<Map<String, Object>>(answers.size());
        for (int i = 0; i < answers.size(); i++) {
            var entry = new TreeMap<String, Object>();
            entry.put("questionNumber", i + 1);
            entry.put("answer", answerValue(answers.get(i)));
            entries.add(entry);
        }
        return json.writeValueAsString(entries);
    }
}
