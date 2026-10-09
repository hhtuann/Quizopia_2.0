package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.quiz.domain.markdown.QuizContent;
import com.quizopia.quiz.domain.markdown.QuizMarkdownError;
import com.quizopia.quiz.domain.markdown.QuizMarkdownParseResult;
import com.quizopia.quiz.domain.markdown.QuizMarkdownParser;
import com.quizopia.quiz.domain.markdown.QuizOption;
import com.quizopia.quiz.domain.markdown.QuizOptionLabel;
import com.quizopia.quiz.domain.markdown.QuizQuestion;
import com.quizopia.quiz.domain.markdown.QuizQuestionType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuizMarkdownParserTest {
    private final QuizMarkdownParser parser = new QuizMarkdownParser();

    @Test
    void preservesDisplayMathWithoutInterpretingStructuralMarkers() {
        String source =
                """
                Câu 1 [SINGLE_CHOICE]: Compute $x^2$
                $$
                Câu 99 [NUMERIC_FILL]: x^2
                $$
                *A. answer
                $$
                B. x+y
                $$
                B. second
                C. third
                D. fourth
                Lời giải: Reason
                $$
                *A. x^2
                $$
                """;

        QuizContent content = valid(source);
        assertEquals(1, content.questions().size());
        QuizQuestion question = content.questions().getFirst();
        assertTrue(question.stemMarkdown().contains("Câu 99 [NUMERIC_FILL]: x^2"));
        assertTrue(question.options().getFirst().markdown().contains("B. x+y"));
        assertEquals(QuizOptionLabel.B, question.options().get(1).label());
        assertTrue(question.explanationMarkdown().contains("*A. x^2"));
    }

    @Test
    void rejectsUnterminatedDisplayMathWithSourceLine() {
        QuizMarkdownParseResult result = parser.parse("Câu 1 [NUMERIC_FILL]: x\n$$\nCâu 2 [NUMERIC_FILL]: fake");
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.code().equals("UNCLOSED_MATH_BLOCK") && error.line() == 2));
    }

    @Test
    void parsesAllFourTypesMultilineContentAndOptionalExplanations() {
        String source =
                """
                Câu 1 [SINGLE_CHOICE]: Dòng đầu câu hỏi 1
                Dòng thứ hai câu hỏi 1.
                *A. Phương án A dòng 1
                Phương án A dòng 2.
                B. Phương án B
                C. Phương án C
                D. Phương án D
                Lời giải: Giải thích dòng 1
                Giải thích dòng 2.
                Câu 2 [MULTIPLE_CHOICE]: Chọn nhiều đáp án.
                *A. Một
                *B. Hai
                C. Ba
                D. Bốn
                Câu 3 [TRUE_FALSE_MATRIX]: Đánh dấu đúng sai.
                A. Sai A
                B. Sai B
                C. Sai C
                D. Sai D
                Câu 4 [NUMERIC_FILL]: Kết quả là bao nhiêu?
                Đáp án: 2.50
                Lời giải:
                `10 / 4 = 2.5`.
                """;

        QuizContent content = valid(source);

        assertEquals(4, content.questions().size());
        QuizQuestion single = content.questions().get(0);
        assertEquals(1, single.number());
        assertEquals(QuizQuestionType.SINGLE_CHOICE, single.type());
        assertEquals("Dòng đầu câu hỏi 1\nDòng thứ hai câu hỏi 1.", single.stemMarkdown());
        assertEquals(
                "Phương án A dòng 1\nPhương án A dòng 2.",
                single.options().get(0).markdown());
        assertTrue(single.options().get(0).correct());
        assertEquals("Giải thích dòng 1\nGiải thích dòng 2.", single.explanationMarkdown());

        QuizQuestion multiple = content.questions().get(1);
        assertEquals(
                2,
                multiple.options().stream().filter(option -> option.correct()).count());

        QuizQuestion matrix = content.questions().get(2);
        assertTrue(matrix.options().stream().noneMatch(option -> option.correct()));

        QuizQuestion numeric = content.questions().get(3);
        assertEquals(QuizQuestionType.NUMERIC_FILL, numeric.type());
        assertEquals(List.of(), numeric.options());
        assertEquals("2.50", numeric.numericAnswer());
        assertEquals("\n`10 / 4 = 2.5`.\n", numeric.explanationMarkdown());
    }

    @Test
    void structuralLookingTextInsideFencesIsContentAcrossStemOptionAndExplanation() {
        String source =
                """
                Câu 1 [SINGLE_CHOICE]: Xem đoạn sau:
                ```text
                Câu 99 [MULTIPLE_CHOICE]: fake
                *A. fake
                Đáp án: 1234
                Lời giải: fake
                ```
                Sau fence vẫn là stem.
                *A. Đáp án thật có code:
                ```java
                B. not an option
                System.out.println("Câu 2 [NUMERIC_FILL]:");
                ```
                B. B thật
                C. C thật
                D. D thật
                Lời giải:
                ```text
                Câu 2 [SINGLE_CHOICE]: vẫn chỉ là code
                A. fake
                ```
                Kết thúc lời giải.
                Câu 2 [NUMERIC_FILL]: Giá trị?
                Đáp án: 1234
                """;

        QuizContent content = valid(source);

        assertEquals(2, content.questions().size());
        QuizQuestion first = content.questions().getFirst();
        assertTrue(first.stemMarkdown().contains("Câu 99 [MULTIPLE_CHOICE]: fake"));
        assertTrue(first.stemMarkdown().contains("Sau fence vẫn là stem."));
        assertTrue(first.options().getFirst().markdown().contains("B. not an option"));
        assertTrue(first.explanationMarkdown().contains("Câu 2 [SINGLE_CHOICE]: vẫn chỉ là code"));
        assertEquals(QuizOptionLabel.B, first.options().get(1).label());
    }

    @Test
    void indentedStructuralLookalikesRemainOrdinaryContent() {
        String source =
                """
                Câu 1 [SINGLE_CHOICE]: Stem
                  Câu 99 [NUMERIC_FILL]: not a header
                  A. not an option
                  Lời giải: not an explanation
                *A. real A
                B. real B
                C. real C
                D. real D
                """;

        QuizQuestion question = valid(source).questions().getFirst();

        assertTrue(question.stemMarkdown().contains("  Câu 99 [NUMERIC_FILL]: not a header"));
        assertTrue(question.stemMarkdown().contains("  A. not an option"));
        assertTrue(question.stemMarkdown().contains("  Lời giải: not an explanation"));
        assertEquals(4, question.options().size());
        assertEquals(null, question.explanationMarkdown());
    }

    @Test
    void acceptsCrLfInputAndTrimsOnlyNumericTokenSurroundingWhitespace() {
        String source = "Câu 1 [NUMERIC_FILL]: Value?\r\nĐáp án: \t-3.5  \r\n";

        QuizQuestion question = valid(source).questions().getFirst();

        assertEquals("Value?", question.stemMarkdown());
        assertEquals("-3.5", question.numericAnswer());
    }

    @Test
    void noExplanationIsValid() {
        QuizQuestion question = valid(singleChoice("*A. correct", "B. b", "C. c", "D. d"))
                .questions()
                .getFirst();

        assertEquals(null, question.explanationMarkdown());
    }

    @ParameterizedTest(name = "numeric token {0}")
    @MethodSource("validNumericTokens")
    void acceptsEveryRepresentativeValidNumericToken(String token) {
        QuizQuestion question = valid(numeric(token)).questions().getFirst();

        assertEquals(token, question.numericAnswer());
    }

    @ParameterizedTest(name = "reject numeric token {0}")
    @MethodSource("invalidNumericTokens")
    void rejectsInvalidNumericTokens(String token) {
        assertError(numeric(token), "NUMERIC_ANSWER_INVALID");
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidStructures")
    void rejectsMalformedStructure(String source, String expectedCode) {
        assertError(source, expectedCode);
    }

    @Test
    void numericAnswerMustBeOnSameLine() {
        assertError(
                """
                Câu 1 [NUMERIC_FILL]: Value?
                Đáp án:
                2.50
                """,
                "NUMERIC_ANSWER_INVALID");
    }

    @Test
    void reportsUsefulQuestionLineAndColumnForUnknownType() {
        QuizMarkdownParseResult result = parser.parse(
                """
                Câu 1 [single_choice]: stem
                A. a
                B. b
                C. c
                D. d
                """);

        QuizMarkdownError error = error(result, "UNKNOWN_QUESTION_TYPE");
        assertEquals(1, error.questionNumber());
        assertEquals(1, error.line());
        assertEquals(8, error.column());
        assertFalse(error.message().isBlank());
    }

    @Test
    void malformedHeaderUsesItsOwnQuestionNumberRatherThanPreviousQuestion() {
        QuizMarkdownParseResult result = parser.parse(
                """
                Câu 1 [NUMERIC_FILL]: first
                Đáp án: 1234
                Câu 2 [SINGLE_CHOICE]
                """);

        QuizMarkdownError error = error(result, "MALFORMED_QUESTION_HEADER");
        assertEquals(2, error.questionNumber());
        assertEquals(3, error.line());
    }

    @Test
    void structuredQuestionDomainModelRejectsParserInvariantBypass() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new QuizQuestion(1, QuizQuestionType.NUMERIC_FILL, "stem", List.of(), "-1.23", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new QuizQuestion(
                        1,
                        QuizQuestionType.NUMERIC_FILL,
                        "stem",
                        List.of(new QuizOption(QuizOptionLabel.A, "not allowed", false)),
                        "1234",
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new QuizQuestion(1, QuizQuestionType.SINGLE_CHOICE, "stem", List.of(), null, null));

        QuizQuestion normalized =
                new QuizQuestion(1, QuizQuestionType.NUMERIC_FILL, "stem", List.of(), " \t-.50  ", null);
        assertEquals("-.50", normalized.numericAnswer());
    }

    @Test
    void reportsFenceOpeningLineForUnclosedFence() {
        QuizMarkdownParseResult result = parser.parse(
                """
                Câu 1 [SINGLE_CHOICE]: stem
                ```java
                Câu 99 [NUMERIC_FILL]: fake
                """);

        QuizMarkdownError error = error(result, "UNCLOSED_CODE_FENCE");
        assertEquals(1, error.questionNumber());
        assertEquals(2, error.line());
        assertEquals(1, error.column());
    }

    private QuizContent valid(String source) {
        QuizMarkdownParseResult result = parser.parse(source);
        assertTrue(result.isValid(), () -> "Expected valid source but got: " + result.errors());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.content().isPresent());
        return result.content().orElseThrow();
    }

    private void assertError(String source, String code) {
        QuizMarkdownParseResult result = parser.parse(source);
        assertFalse(result.isValid(), () -> "Expected error " + code + " but source was valid");
        assertTrue(result.content().isEmpty());
        assertNotNull(error(result, code));
    }

    private static QuizMarkdownError error(QuizMarkdownParseResult result, String code) {
        return result.errors().stream()
                .filter(candidate -> candidate.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing error " + code + ": " + result.errors()));
    }

    private static String singleChoice(String a, String b, String c, String d) {
        return "Câu 1 [SINGLE_CHOICE]: stem\n" + a + "\n" + b + "\n" + c + "\n" + d + "\n";
    }

    private static String numeric(String token) {
        return "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: " + token + "\n";
    }

    private static Stream<String> validNumericTokens() {
        return Stream.of("1234", "0001", "2.50", "0.25", "-3.5", "-0.5", "12.3", "-123", "-.50");
    }

    private static Stream<String> invalidNumericTokens() {
        return Stream.of(
                "123", "12345", "+123", "1,25", ".250", "250.", "1..2", "--12", "1 23", "１２３４", "1e03", "12-3",
                "-1.23");
    }

    private static Stream<Arguments> invalidStructures() {
        return Stream.of(
                Arguments.of("", "NO_QUESTIONS"),
                Arguments.of("plain text", "CONTENT_OUTSIDE_QUESTION"),
                Arguments.of("Câu 0 [NUMERIC_FILL]: value?\nĐáp án: 1234\n", "QUESTION_NUMBER_MUST_START_AT_ONE"),
                Arguments.of("Câu 2 [NUMERIC_FILL]: value?\nĐáp án: 1234\n", "QUESTION_NUMBER_MUST_START_AT_ONE"),
                Arguments.of(
                        "Câu 1 [NUMERIC_FILL]: one\nĐáp án: 1234\nCâu 3 [NUMERIC_FILL]: three\nĐáp án: 1234\n",
                        "QUESTION_NUMBER_GAP"),
                Arguments.of(
                        "Câu 1 [NUMERIC_FILL]: one\nĐáp án: 1234\nCâu 1 [NUMERIC_FILL]: again\nĐáp án: 1234\n",
                        "DUPLICATE_QUESTION_NUMBER"),
                Arguments.of("Câu 1 [UNKNOWN]: stem\nA. a\nB. b\nC. c\nD. d\n", "UNKNOWN_QUESTION_TYPE"),
                Arguments.of("Câu 1 [single_choice]: stem\nA. a\nB. b\nC. c\nD. d\n", "UNKNOWN_QUESTION_TYPE"),
                Arguments.of("Câu 1 [SINGLE_CHOICE]:\n*A. a\nB. b\nC. c\nD. d\n", "EMPTY_STEM"),
                Arguments.of(singleChoice("*A. a", "C. c", "D. d", "D. duplicate"), "MISSING_OPTION"),
                Arguments.of(singleChoice("*A. a", "C. c", "B. b", "D. d"), "MISSING_OPTION"),
                Arguments.of(singleChoice("*A. a", "B. b", "B. duplicate", "D. d"), "DUPLICATE_OPTION"),
                Arguments.of(singleChoice("*A. a", "B.", "C. c", "D. d"), "EMPTY_OPTION"),
                Arguments.of(singleChoice("A. a", "B. b", "C. c", "D. d"), "SINGLE_CHOICE_CORRECT_COUNT"),
                Arguments.of(singleChoice("*A. a", "*B. b", "C. c", "D. d"), "SINGLE_CHOICE_CORRECT_COUNT"),
                Arguments.of(
                        "Câu 1 [MULTIPLE_CHOICE]: stem\nA. a\nB. b\nC. c\nD. d\n", "MULTIPLE_CHOICE_CORRECT_REQUIRED"),
                Arguments.of("Câu 1 [NUMERIC_FILL]: stem\nA. invalid\nĐáp án: 1234\n", "UNEXPECTED_OPTION"),
                Arguments.of(
                        "Câu 1 [SINGLE_CHOICE]: stem\n*A. a\nB. b\nC. c\nD. d\nĐáp án: 1234\n",
                        "UNEXPECTED_NUMERIC_ANSWER"),
                Arguments.of(
                        "Câu 1 [SINGLE_CHOICE]: stem\nLời giải: too early\n*A. a\nB. b\nC. c\nD. d\n",
                        "EXPLANATION_BEFORE_ANSWER_STRUCTURE"),
                Arguments.of(
                        "Câu 1 [SINGLE_CHOICE]: stem\n*A. a\nB. b\nC. c\nD. d\nLời giải: first\nLời giải: second\n",
                        "DUPLICATE_EXPLANATION"),
                Arguments.of("Câu 1 [SINGLE_CHOICE]: stem\n```java\nint x = 1;\n", "UNCLOSED_CODE_FENCE"));
    }
}
