package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quizopia.quiz.application.QuizApplicationService;
import com.quizopia.quiz.application.QuizDraftDetails;
import com.quizopia.quiz.application.QuizDraftInput;
import com.quizopia.quiz.application.QuizDraftNotFoundException;
import com.quizopia.quiz.application.QuizDraftRepository;
import com.quizopia.quiz.application.QuizIdGenerator;
import com.quizopia.quiz.application.QuizNotFoundException;
import com.quizopia.quiz.application.QuizOwnershipDeniedException;
import com.quizopia.quiz.application.QuizRepository;
import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDraft;
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
    private final RecordingQuizRepository quizzes = new RecordingQuizRepository();
    private final RecordingQuizDraftRepository drafts = new RecordingQuizDraftRepository();
    private final QuizIdGenerator idGenerator = () -> generatedQuizId;
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

    private QuizApplicationService serviceAt(Instant instant) {
        return new QuizApplicationService(quizzes, drafts, idGenerator, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private Quiz quiz(UUID ownerUserId) {
        return new Quiz(UUID.randomUUID(), ownerUserId, INITIAL_TIME);
    }

    private QuizDraft draft(UUID quizId, String source) {
        return new QuizDraft(quizId, "Title", "Description", source, INITIAL_TIME);
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
}
