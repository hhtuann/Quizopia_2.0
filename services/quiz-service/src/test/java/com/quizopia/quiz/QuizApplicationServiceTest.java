package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.quiz.application.QuizApplicationService;
import com.quizopia.quiz.application.QuizDraftDetails;
import com.quizopia.quiz.application.QuizDraftInput;
import com.quizopia.quiz.application.QuizDraftNotFoundException;
import com.quizopia.quiz.application.QuizDraftRepository;
import com.quizopia.quiz.application.QuizIdGenerator;
import com.quizopia.quiz.application.QuizMarkdownInvalidException;
import com.quizopia.quiz.application.QuizNotFoundException;
import com.quizopia.quiz.application.QuizOwnershipDeniedException;
import com.quizopia.quiz.application.QuizPublishResult;
import com.quizopia.quiz.application.QuizRepository;
import com.quizopia.quiz.application.QuizVersionIdGenerator;
import com.quizopia.quiz.application.QuizVersionRepository;
import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDraft;
import com.quizopia.quiz.domain.QuizVersion;
import com.quizopia.quiz.domain.markdown.QuizMarkdownParser;
import com.quizopia.quiz.domain.markdown.QuizQuestionType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuizApplicationServiceTest {
    private static final Instant INITIAL_TIME = Instant.parse("2026-09-26T01:02:03.123456Z");
    private static final Instant UPDATED_TIME = Instant.parse("2026-09-26T04:05:06.654321Z");

    private final UUID generatedQuizId = UUID.randomUUID();
    private final UUID generatedVersionId = UUID.randomUUID();
    private final RecordingQuizRepository quizzes = new RecordingQuizRepository();
    private final RecordingQuizDraftRepository drafts = new RecordingQuizDraftRepository();
    private final RecordingQuizVersionRepository versions = new RecordingQuizVersionRepository();
    private final QuizIdGenerator idGenerator = () -> generatedQuizId;
    private final QuizVersionIdGenerator versionIdGenerator = () -> generatedVersionId;
    private final QuizMarkdownParser markdownParser = new QuizMarkdownParser();
    private QuizApplicationService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(INITIAL_TIME);
    }

    @Test
    void createsQuizAndInitialDraftWithGeneratedIdCallerOwnershipAndControlledTime() {
        UUID callerUserId = UUID.randomUUID();
        String source = "  opaque source\r\n*kept exactly*  ";

        QuizDraftDetails result = service.create(callerUserId, new QuizDraftInput("Title", "Description", source));

        assertEquals(generatedQuizId, result.quiz().id());
        assertEquals(generatedQuizId, result.draft().quizId());
        assertEquals(callerUserId, result.quiz().ownerUserId());
        assertEquals(INITIAL_TIME, result.quiz().createdAt());
        assertEquals(INITIAL_TIME, result.draft().updatedAt());
        assertEquals("Title", result.draft().title());
        assertEquals("Description", result.draft().description());
        assertEquals(source, result.draft().authoringSource());
        assertSame(result.quiz(), quizzes.lastInserted);
        assertSame(result.draft(), drafts.lastSaved);
        assertEquals(1, quizzes.insertCount);
        assertEquals(1, drafts.saveCount);
    }

    @Test
    void createInputContainsOnlyAcceptedMutableDraftFields() {
        assertEquals(
                java.util.List.of("title", "description", "authoringSource"),
                java.util.Arrays.stream(QuizDraftInput.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
    }

    @Test
    void createPreservesNullableTextWithoutInventingPolicy() {
        QuizDraftDetails result = service.create(UUID.randomUUID(), new QuizDraftInput(null, null, null));

        assertNull(result.draft().title());
        assertNull(result.draft().description());
        assertNull(result.draft().authoringSource());
    }

    @Test
    void ownerCanReadOwnDraft() {
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = quiz(ownerUserId);
        QuizDraft draft = draft(quiz.id(), "source");
        quizzes.insert(quiz);
        drafts.save(draft);

        QuizDraftDetails result = service.getOwnedDraft(UUID.fromString(ownerUserId.toString()), quiz.id());

        assertSame(quiz, result.quiz());
        assertSame(draft, result.draft());
    }

    @Test
    void missingQuizReadFailsBeforeDraftLookup() {
        UUID quizId = UUID.randomUUID();

        QuizNotFoundException exception =
                assertThrows(QuizNotFoundException.class, () -> service.getOwnedDraft(UUID.randomUUID(), quizId));

        assertEquals(quizId, exception.quizId());
        assertEquals(0, drafts.findCount);
    }

    @Test
    void nonOwnerReadIsDeniedUsingStableQuizBeforeDraftLookup() {
        Quiz quiz = quiz(UUID.randomUUID());
        quizzes.insert(quiz);
        drafts.save(draft(quiz.id(), "source"));
        UUID callerUserId = UUID.randomUUID();

        QuizOwnershipDeniedException exception =
                assertThrows(QuizOwnershipDeniedException.class, () -> service.getOwnedDraft(callerUserId, quiz.id()));

        assertEquals(quiz.id(), exception.quizId());
        assertEquals(callerUserId, exception.callerUserId());
        assertEquals(0, drafts.findCount);
    }

    @Test
    void missingDraftHasExplicitFailureAfterQuizOwnershipIsEstablished() {
        Quiz quiz = quiz(UUID.randomUUID());
        quizzes.insert(quiz);

        QuizDraftNotFoundException exception = assertThrows(
                QuizDraftNotFoundException.class, () -> service.getOwnedDraft(quiz.ownerUserId(), quiz.id()));

        assertEquals(quiz.id(), exception.quizId());
        assertEquals(1, drafts.findCount);
    }

    @Test
    void ownerUpdatesDraftExactlyAndLeavesStableQuizUnchanged() {
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = quiz(ownerUserId);
        QuizDraft originalDraft = draft(quiz.id(), "old source");
        quizzes.insert(quiz);
        drafts.save(originalDraft);
        drafts.resetCounts();
        String updatedSource = "\r\n  new [unparsed] source\t";
        service = serviceAt(UPDATED_TIME);

        QuizDraftDetails result =
                service.updateOwnedDraft(ownerUserId, quiz.id(), new QuizDraftInput("Changed", null, updatedSource));

        assertSame(quiz, result.quiz());
        assertEquals(quiz.id(), result.draft().quizId());
        assertEquals("Changed", result.draft().title());
        assertNull(result.draft().description());
        assertEquals(updatedSource, result.draft().authoringSource());
        assertEquals(UPDATED_TIME, result.draft().updatedAt());
        assertEquals(quiz, quizzes.findById(quiz.id()).orElseThrow());
        assertEquals(result.draft(), drafts.findByQuizId(quiz.id()).orElseThrow());
        assertEquals(quiz.id(), result.quiz().id());
        assertEquals(ownerUserId, result.quiz().ownerUserId());
        assertEquals(INITIAL_TIME, result.quiz().createdAt());
        assertEquals(1, quizzes.insertCount);
        assertEquals(1, drafts.saveCount);
    }

    @Test
    void nonOwnerCannotUpdateAndDraftIsNeitherLoadedNorSaved() {
        Quiz quiz = quiz(UUID.randomUUID());
        QuizDraft originalDraft = draft(quiz.id(), "original");
        quizzes.insert(quiz);
        drafts.save(originalDraft);
        drafts.resetCounts();

        assertThrows(
                QuizOwnershipDeniedException.class,
                () -> service.updateOwnedDraft(
                        UUID.randomUUID(), quiz.id(), new QuizDraftInput("Changed", "Changed", "changed")));

        assertEquals(0, drafts.findCount);
        assertEquals(0, drafts.saveCount);
        assertSame(originalDraft, drafts.values.get(quiz.id()));
    }

    @Test
    void nonexistentQuizCannotUpdateAndDraftIsNeitherLoadedNorSaved() {
        UUID quizId = UUID.randomUUID();

        QuizNotFoundException exception = assertThrows(
                QuizNotFoundException.class,
                () -> service.updateOwnedDraft(
                        UUID.randomUUID(), quizId, new QuizDraftInput("Changed", "Changed", "changed")));

        assertEquals(quizId, exception.quizId());
        assertEquals(0, drafts.findCount);
        assertEquals(0, drafts.saveCount);
    }

    @Test
    void firstPublishCreatesV1FromExactCurrentDraftSnapshot() {
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = quiz(ownerUserId);
        String source = "Câu 1 [NUMERIC_FILL]: Giá trị?\r\nĐáp án: -.50\r\n";
        QuizDraft currentDraft = new QuizDraft(quiz.id(), "Title", "Description", source, INITIAL_TIME);
        quizzes.insert(quiz);
        drafts.save(currentDraft);

        QuizPublishResult result = service.publishOwnedDraft(ownerUserId, quiz.id());

        assertTrue(result.created());
        assertEquals(generatedVersionId, result.version().id());
        assertEquals(1, result.version().versionNumber());
        assertEquals("Title", result.version().titleSnapshot());
        assertEquals("Description", result.version().descriptionSnapshot());
        assertEquals(source, result.version().sourceSnapshot());
        assertEquals(1, result.version().contentSchemaVersion());
        assertEquals(INITIAL_TIME, result.version().createdAt());
        assertEquals(
                QuizQuestionType.NUMERIC_FILL,
                result.version().structuredContent().questions().get(0).type());
        assertEquals(
                "-.50", result.version().structuredContent().questions().get(0).numericAnswer());
        assertEquals(currentDraft, drafts.values.get(quiz.id()));
        assertEquals(1, versions.insertCount);
    }

    @Test
    void unchangedDraftReusesOnlyLatestVersion() {
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = quiz(ownerUserId);
        String source = validSingleChoiceSource("same");
        quizzes.insert(quiz);
        drafts.save(new QuizDraft(quiz.id(), "Title", null, source, INITIAL_TIME));

        QuizPublishResult first = service.publishOwnedDraft(ownerUserId, quiz.id());
        QuizPublishResult second = service.publishOwnedDraft(ownerUserId, quiz.id());

        assertTrue(first.created());
        assertEquals(false, second.created());
        assertEquals(first.version(), second.version());
        assertEquals(1, versions.insertCount);
    }

    @Test
    void changedDraftCreatesNextVersionAndHistoricalMatchCreatesNewVersionAgain() {
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = quiz(ownerUserId);
        String sourceA = validSingleChoiceSource("A");
        String sourceB = validSingleChoiceSource("B");
        quizzes.insert(quiz);
        drafts.save(new QuizDraft(quiz.id(), "A", "desc-a", sourceA, INITIAL_TIME));

        QuizPublishResult v1 = service.publishOwnedDraft(ownerUserId, quiz.id());
        service = serviceAt(UPDATED_TIME);
        drafts.save(new QuizDraft(quiz.id(), "B", "desc-b", sourceB, UPDATED_TIME));
        QuizPublishResult v2 = service.publishOwnedDraft(ownerUserId, quiz.id());
        drafts.save(new QuizDraft(quiz.id(), "A", "desc-a", sourceA, UPDATED_TIME));
        QuizPublishResult v3 = service.publishOwnedDraft(ownerUserId, quiz.id());

        assertEquals(1, v1.version().versionNumber());
        assertEquals(2, v2.version().versionNumber());
        assertEquals(3, v3.version().versionNumber());
        assertEquals(sourceA, v1.version().sourceSnapshot());
        assertEquals(sourceB, v2.version().sourceSnapshot());
        assertEquals(sourceA, v3.version().sourceSnapshot());
        assertEquals(3, versions.insertCount);
    }

    @Test
    void invalidMarkdownCreatesNoVersionAndDoesNotMutateDraft() {
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = quiz(ownerUserId);
        QuizDraft currentDraft = new QuizDraft(quiz.id(), "Title", null, "not quiz markdown", INITIAL_TIME);
        quizzes.insert(quiz);
        drafts.save(currentDraft);

        QuizMarkdownInvalidException exception = assertThrows(
                QuizMarkdownInvalidException.class, () -> service.publishOwnedDraft(ownerUserId, quiz.id()));

        assertTrue(exception.errors().stream().anyMatch(error -> error.code().equals("CONTENT_OUTSIDE_QUESTION")));
        assertEquals(0, versions.insertCount);
        assertSame(currentDraft, drafts.values.get(quiz.id()));
    }

    @Test
    void laterDraftEditsCannotMutateAlreadyPublishedVersion() {
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = quiz(ownerUserId);
        String originalSource = validSingleChoiceSource("original");
        quizzes.insert(quiz);
        drafts.save(new QuizDraft(quiz.id(), "Original", "Original description", originalSource, INITIAL_TIME));
        QuizVersion published =
                service.publishOwnedDraft(ownerUserId, quiz.id()).version();

        service = serviceAt(UPDATED_TIME);
        service.updateOwnedDraft(
                ownerUserId,
                quiz.id(),
                new QuizDraftInput("Changed", "Changed description", validSingleChoiceSource("changed")));

        assertEquals("Original", published.titleSnapshot());
        assertEquals("Original description", published.descriptionSnapshot());
        assertEquals(originalSource, published.sourceSnapshot());
        assertEquals(originalSource, versions.values.get(0).sourceSnapshot());
    }

    @Test
    void nonOwnerCannotPublishAndDraftOrVersionsAreNotRead() {
        Quiz quiz = quiz(UUID.randomUUID());
        quizzes.insert(quiz);
        drafts.save(new QuizDraft(quiz.id(), "Title", null, validSingleChoiceSource("x"), INITIAL_TIME));
        drafts.resetCounts();

        assertThrows(QuizOwnershipDeniedException.class, () -> service.publishOwnedDraft(UUID.randomUUID(), quiz.id()));

        assertEquals(0, drafts.findCount);
        assertEquals(0, versions.findLatestCount);
        assertEquals(0, versions.insertCount);
    }

    private QuizApplicationService serviceAt(Instant instant) {
        return new QuizApplicationService(
                quizzes,
                drafts,
                versions,
                idGenerator,
                versionIdGenerator,
                markdownParser,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private Quiz quiz(UUID ownerUserId) {
        return new Quiz(UUID.randomUUID(), ownerUserId, INITIAL_TIME);
    }

    private QuizDraft draft(UUID quizId, String source) {
        return new QuizDraft(quizId, "Title", "Description", source, INITIAL_TIME);
    }

    private static String validSingleChoiceSource(String marker) {
        return "Câu 1 [SINGLE_CHOICE]: " + marker + "\n*A. A\nB. B\nC. C\nD. D\n";
    }

    private static final class RecordingQuizRepository implements QuizRepository {
        private final Map<UUID, Quiz> values = new LinkedHashMap<>();
        private Quiz lastInserted;
        private int insertCount;

        @Override
        public void insert(Quiz quiz) {
            lastInserted = quiz;
            insertCount++;
            values.put(quiz.id(), quiz);
        }

        @Override
        public Optional<Quiz> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public Optional<Quiz> findByIdForUpdate(UUID id) {
            return findById(id);
        }
    }

    private static final class RecordingQuizDraftRepository implements QuizDraftRepository {
        private final Map<UUID, QuizDraft> values = new LinkedHashMap<>();
        private QuizDraft lastSaved;
        private int saveCount;
        private int findCount;

        @Override
        public void save(QuizDraft draft) {
            lastSaved = draft;
            saveCount++;
            values.put(draft.quizId(), draft);
        }

        @Override
        public Optional<QuizDraft> findByQuizId(UUID quizId) {
            findCount++;
            return Optional.ofNullable(values.get(quizId));
        }

        private void resetCounts() {
            saveCount = 0;
            findCount = 0;
        }
    }

    private static final class RecordingQuizVersionRepository implements QuizVersionRepository {
        private final java.util.List<QuizVersion> values = new java.util.ArrayList<>();
        private int insertCount;
        private int findLatestCount;

        @Override
        public void insert(QuizVersion version) {
            insertCount++;
            values.add(version);
        }

        @Override
        public Optional<QuizVersion> findLatestByQuizId(UUID quizId) {
            findLatestCount++;
            return values.stream()
                    .filter(version -> version.quizId().equals(quizId))
                    .max(java.util.Comparator.comparingInt(QuizVersion::versionNumber));
        }
    }
}
