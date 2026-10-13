package com.quizopia.assessment.domain.grading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Pure exact v1 computation. Protected inputs and question outcomes are never public DTOs. */
public final class GradingPolicy {
    public static final int VERSION = 1;
    public static final String NAME = "QUIZOPIA_GRADING_V1";
    private static final List<String> LABELS = List.of("A", "B", "C", "D");

    public enum Type {
        SINGLE_CHOICE,
        MULTIPLE_CHOICE,
        TRUE_FALSE_MATRIX,
        NUMERIC_FILL
    }

    public sealed interface Answer permits Single, Multiple, Matrix, Numeric {
        Type type();
    }

    public record Single(String label) implements Answer {
        public Single {
            if (label != null && !LABELS.contains(label)) throw invalid();
        }

        public Type type() {
            return Type.SINGLE_CHOICE;
        }

        public String toString() {
            return "Single[redacted]";
        }
    }

    public record Multiple(List<String> labels) implements Answer {
        public Multiple {
            if (labels == null
                    || labels.stream().anyMatch(v -> v == null || !LABELS.contains(v))
                    || new HashSet<>(labels).size() != labels.size()) throw invalid();
            labels = labels.stream().sorted().toList();
        }

        public Type type() {
            return Type.MULTIPLE_CHOICE;
        }

        public String toString() {
            return "Multiple[redacted]";
        }
    }

    public record Matrix(List<Boolean> judgments) implements Answer {
        public Matrix {
            if (judgments == null || judgments.size() != 4) throw invalid();
            judgments = Collections.unmodifiableList(new ArrayList<>(judgments));
        }

        public Type type() {
            return Type.TRUE_FALSE_MATRIX;
        }

        public String toString() {
            return "Matrix[redacted]";
        }
    }

    public record Numeric(String token) implements Answer {
        public Numeric {
            token = numericToken(token);
        }

        public Type type() {
            return Type.NUMERIC_FILL;
        }

        public String toString() {
            return "Numeric[redacted]";
        }
    }

    public record Question(int number, Answer key) {
        public Question {
            if (number < 1
                    || key == null
                    || key instanceof Single s && s.label() == null
                    || key instanceof Multiple m && m.labels().isEmpty()
                    || key instanceof Matrix m && m.judgments().contains(null)
                    || key instanceof Numeric n && n.token() == null) throw unavailable();
        }

        public String toString() {
            return "Question[redacted]";
        }
    }

    public record Snapshot(int policyVersion, List<Question> questions) {
        public Snapshot {
            if (policyVersion != VERSION || questions == null || questions.isEmpty()) throw unavailable();
            questions = List.copyOf(questions);
            for (int i = 0; i < questions.size(); i++) if (questions.get(i).number() != i + 1) throw unavailable();
        }

        public String toString() {
            return "Snapshot[redacted]";
        }
    }

    public record Score(List<BigDecimal> questionWeights, BigDecimal earned, BigDecimal possible) {
        public Score {
            questionWeights = List.copyOf(questionWeights);
        }

        public String toString() {
            return "Score[redacted]";
        }
    }

    public static final class Failure extends RuntimeException {
        private final boolean unavailable;

        private Failure(boolean unavailable) {
            super(unavailable ? "GRADING_UNAVAILABLE" : "INVALID_REQUEST", null, false, false);
            this.unavailable = unavailable;
        }

        public boolean unavailable() {
            return unavailable;
        }
    }

    public static Failure invalid() {
        return new Failure(false);
    }

    public static Failure unavailable() {
        return new Failure(true);
    }

    public static String numericToken(String value) {
        if (value == null) return null;
        int start = 0, end = value.length();
        while (start < end && asciiWhitespace(value.charAt(start))) start++;
        while (end > start && asciiWhitespace(value.charAt(end - 1))) end--;
        if (start == end) return null;
        String token = value.substring(start, end);
        if (token.length() != 4 || !token.matches("(?:-?[0-9]+(?:[.][0-9]+)?|-[.][0-9]+)")) throw invalid();
        return token;
    }

    private static boolean asciiWhitespace(char c) {
        return c == ' ' || c >= '\t' && c <= '\r';
    }

    public static Answer unanswered(Type type) {
        return switch (type) {
            case SINGLE_CHOICE -> new Single(null);
            case MULTIPLE_CHOICE -> new Multiple(List.of());
            case TRUE_FALSE_MATRIX -> new Matrix(Arrays.asList(null, null, null, null));
            case NUMERIC_FILL -> new Numeric(null);
        };
    }

    public Score grade(Snapshot snapshot, List<Answer> answers) {
        if (snapshot == null) throw unavailable();
        if (answers == null || answers.size() != snapshot.questions().size()) throw invalid();
        var weights = new ArrayList<BigDecimal>(answers.size());
        int quarters = 0;
        for (int i = 0; i < answers.size(); i++) {
            Answer key = snapshot.questions().get(i).key(), answer = answers.get(i);
            if (answer == null || answer.type() != key.type()) throw invalid();
            int earned =
                    switch (key) {
                        case Single k -> Objects.equals(k.label(), ((Single) answer).label()) ? 4 : 0;
                        case Multiple k -> k.labels().equals(((Multiple) answer).labels()) ? 4 : 0;
                        case Matrix k -> {
                            int count = 0;
                            var judgments = ((Matrix) answer).judgments();
                            for (int j = 0; j < 4; j++)
                                if (judgments.get(j) != null
                                        && judgments.get(j).equals(k.judgments().get(j))) count++;
                            yield count;
                        }
                        case Numeric k -> {
                            String token = ((Numeric) answer).token();
                            yield token != null && new BigDecimal(token).compareTo(new BigDecimal(k.token())) == 0
                                    ? 4
                                    : 0;
                        }
                    };
            quarters = Math.addExact(quarters, earned);
            weights.add(BigDecimal.valueOf(earned).divide(BigDecimal.valueOf(4)));
        }
        return new Score(
                weights,
                BigDecimal.valueOf(quarters).divide(BigDecimal.valueOf(4)),
                BigDecimal.valueOf(answers.size()));
    }

    public static String decimal(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    public static String percentage(BigDecimal earned, BigDecimal possible) {
        if (earned == null
                || possible == null
                || possible.signum() <= 0
                || earned.signum() < 0
                || earned.compareTo(possible) > 0) throw invalid();
        return earned.multiply(BigDecimal.valueOf(100))
                .divide(possible, 2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
