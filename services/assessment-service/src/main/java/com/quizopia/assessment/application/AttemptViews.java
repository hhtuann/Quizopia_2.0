package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.*;

/** Explicit HTTP allowlists; no persistence entity or protected snapshot is serializable here. */
public final class AttemptViews {
    private AttemptViews() {}

    public record Option(String label, String markdown) {}

    public record Question(int number, String type, String stemMarkdown, List<Option> options) {
        public Question {
            options = List.copyOf(options);
        }

        public String toString() {
            return "Question[redacted]";
        }
    }

    public sealed interface Answer permits Single, Multiple, Matrix, Numeric {}

    public record Single(String type, String optionLabel) implements Answer {
        public String toString() {
            return "Answer[redacted]";
        }
    }

    public record Multiple(String type, List<String> optionLabels) implements Answer {
        public Multiple {
            optionLabels = List.copyOf(optionLabels);
        }

        public String toString() {
            return "Answer[redacted]";
        }
    }

    public record Matrix(String type, Map<String, Boolean> judgments) implements Answer {
        public Matrix {
            judgments = Collections.unmodifiableMap(new TreeMap<>(judgments));
        }

        public String toString() {
            return "Answer[redacted]";
        }
    }

    public record Numeric(String type, String value) implements Answer {
        public String toString() {
            return "Answer[redacted]";
        }
    }

    public record Entry(int questionNumber, Answer answer) {
        public String toString() {
            return "Entry[redacted]";
        }
    }

    public record Closure(UUID closureRequestId, Instant confirmedNoticeAt, Instant closureCutoffAt) {}

    public record View(
            UUID attemptId,
            UUID publicationId,
            String ordinal,
            String state,
            Instant startedAt,
            Instant deadlineAt,
            int durationMinutes,
            String revision,
            Instant serverNow,
            List<Question> questions,
            List<Entry> answers,
            Closure closure) {
        public View {
            questions = List.copyOf(questions);
            answers = List.copyOf(answers);
        }

        public String toString() {
            return "AttemptView[redacted]";
        }
    }

    public record SaveAck(UUID attemptId, int questionNumber, String revision, Instant savedAt) {}

    public record AbandonAck(
            UUID attemptId, String state, String terminalReason, String revision, Instant finalizedAt) {}

    public record Result(
            UUID resultId,
            UUID attemptId,
            UUID publicationId,
            String ordinal,
            String earnedWeight,
            String possibleWeight,
            String percentageDisplay,
            int gradingPolicyVersion,
            String gradingPolicyId,
            String terminalReason,
            Instant finalizedAt) {
        public String toString() {
            return "Result[redacted]";
        }
    }

    public record Aggregate(UUID publicationId, Result bestResult) {}

    public record QuestionReview(
            Question question,
            Answer answer,
            Answer correctAnswer,
            String earnedWeight,
            String possibleWeight,
            String explanationMarkdown) {
        public String toString() {
            return "QuestionReview[redacted]";
        }
    }

    public record Review(Result result, List<QuestionReview> questions) {
        public Review {
            questions = List.copyOf(questions);
        }

        public String toString() {
            return "Review[redacted]";
        }
    }
}
