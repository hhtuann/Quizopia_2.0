package com.quizopia.quiz.persistence;

import com.quizopia.quiz.domain.markdown.QuizContent;
import com.quizopia.quiz.domain.markdown.QuizOption;
import com.quizopia.quiz.domain.markdown.QuizOptionLabel;
import com.quizopia.quiz.domain.markdown.QuizQuestion;
import com.quizopia.quiz.domain.markdown.QuizQuestionType;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class QuizStructuredContentJson {
    private final ObjectMapper objectMapper;

    QuizStructuredContentJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(QuizContent content) {
        try {
            return objectMapper.writeValueAsString(StoredContent.from(content));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize validated quiz content", exception);
        }
    }

    QuizContent read(String json) {
        try {
            return objectMapper.readValue(json, StoredContent.class).toDomain();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not deserialize persisted quiz content", exception);
        }
    }

    private record StoredContent(List<StoredQuestion> questions) {
        private static StoredContent from(QuizContent content) {
            return new StoredContent(
                    content.questions().stream().map(StoredQuestion::from).toList());
        }

        private QuizContent toDomain() {
            return new QuizContent(
                    questions.stream().map(StoredQuestion::toDomain).toList());
        }
    }

    private record StoredQuestion(
            int number,
            String type,
            String stemMarkdown,
            List<StoredOption> options,
            String numericAnswer,
            String explanationMarkdown) {
        private static StoredQuestion from(QuizQuestion question) {
            return new StoredQuestion(
                    question.number(),
                    question.type().name(),
                    question.stemMarkdown(),
                    question.options().stream().map(StoredOption::from).toList(),
                    question.numericAnswer(),
                    question.explanationMarkdown());
        }

        private QuizQuestion toDomain() {
            return new QuizQuestion(
                    number,
                    QuizQuestionType.valueOf(type),
                    stemMarkdown,
                    options.stream().map(StoredOption::toDomain).toList(),
                    numericAnswer,
                    explanationMarkdown);
        }
    }

    private record StoredOption(String label, String markdown, boolean correct) {
        private static StoredOption from(QuizOption option) {
            return new StoredOption(option.label().name(), option.markdown(), option.correct());
        }

        private QuizOption toDomain() {
            return new QuizOption(QuizOptionLabel.valueOf(label), markdown, correct);
        }
    }
}
