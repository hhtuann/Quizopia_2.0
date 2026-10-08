package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quizopia.quiz.application.InvalidQuizVersionRequestException;
import com.quizopia.quiz.application.QuizNotFoundException;
import com.quizopia.quiz.application.QuizOwnershipDeniedException;
import com.quizopia.quiz.application.QuizRepository;
import com.quizopia.quiz.application.QuizVersionCursorCodec;
import com.quizopia.quiz.application.QuizVersionNotFoundException;
import com.quizopia.quiz.application.QuizVersionPage;
import com.quizopia.quiz.application.QuizVersionQueryService;
import com.quizopia.quiz.application.QuizVersionRepository;
import com.quizopia.quiz.application.QuizVersionSummary;
import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizVersion;
import com.quizopia.quiz.domain.markdown.QuizMarkdownParser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuizVersionQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final QuizVersionRepository versions = mock(QuizVersionRepository.class);
    private final QuizVersionCursorCodec cursorCodec = new QuizVersionCursorCodec();
    private final QuizVersionQueryService service = new QuizVersionQueryService(quizzes, versions, cursorCodec);

    private UUID ownerUserId;
    private UUID quizId;

    @BeforeEach
    void configureOwnedQuiz() {
        ownerUserId = UUID.randomUUID();
        quizId = UUID.randomUUID();
        when(quizzes.findById(quizId)).thenReturn(Optional.of(new Quiz(quizId, ownerUserId, NOW)));
    }

    @Test
    void ownerListsNewestFirstWithOpaqueCursorAndNoDuplicateAcrossPages() {
        QuizVersionSummary version3 = summary(3);
        QuizVersionSummary version2 = summary(2);
        QuizVersionSummary version1 = summary(1);
        when(versions.findByQuizIdBeforeVersionNumber(quizId, null, 3))
                .thenReturn(List.of(version3, version2, version1));

        QuizVersionPage first = service.listOwned(ownerUserId, quizId, 2, null);

        assertEquals(List.of(version3, version2), first.items());
        assertFalse(first.nextCursor().contains("2"));
        assertEquals(2, cursorCodec.decode(first.nextCursor()));

        when(versions.findByQuizIdBeforeVersionNumber(quizId, 2, 3)).thenReturn(List.of(version1));
        QuizVersionPage second = service.listOwned(ownerUserId, quizId, 2, first.nextCursor());

        assertEquals(List.of(version1), second.items());
        assertNull(second.nextCursor());
        verify(quizzes, org.mockito.Mockito.times(2)).findById(quizId);
        verify(versions).findByQuizIdBeforeVersionNumber(quizId, null, 3);
        verify(versions).findByQuizIdBeforeVersionNumber(quizId, 2, 3);
    }

    @Test
    void emptyHistoryReturnsEmptyPage() {
        when(versions.findByQuizIdBeforeVersionNumber(quizId, null, 21)).thenReturn(List.of());

        QuizVersionPage page = service.listOwned(ownerUserId, quizId, 20, null);

        assertEquals(List.of(), page.items());
        assertNull(page.nextCursor());
    }

    @Test
    void invalidLimitAndCursorFailWithoutVersionQuery() {
        assertThrows(InvalidQuizVersionRequestException.class, () -> service.listOwned(ownerUserId, quizId, 0, null));
        assertThrows(InvalidQuizVersionRequestException.class, () -> service.listOwned(ownerUserId, quizId, 101, null));
        assertThrows(
                InvalidQuizVersionRequestException.class,
                () -> service.listOwned(ownerUserId, quizId, 20, "not-a-cursor"));
        verify(versions, never()).findByQuizIdBeforeVersionNumber(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void ownerReadsExactPersistedVersion() {
        QuizVersion version = version(2, "exact historical source\r\n");
        when(versions.findByQuizIdAndVersionNumber(quizId, 2)).thenReturn(Optional.of(version));

        assertEquals(version, service.getOwned(ownerUserId, quizId, 2));
        verify(versions).findByQuizIdAndVersionNumber(quizId, 2);
    }

    @Test
    void missingQuizAndVersionFollowNotFoundConventions() {
        UUID missingQuiz = UUID.randomUUID();
        when(quizzes.findById(missingQuiz)).thenReturn(Optional.empty());
        when(versions.findByQuizIdAndVersionNumber(quizId, 99)).thenReturn(Optional.empty());

        assertThrows(QuizNotFoundException.class, () -> service.listOwned(ownerUserId, missingQuiz, 20, null));
        assertThrows(QuizVersionNotFoundException.class, () -> service.getOwned(ownerUserId, quizId, 99));
        assertThrows(InvalidQuizVersionRequestException.class, () -> service.getOwned(ownerUserId, quizId, 0));
    }

    @Test
    void nonOwnerIsDeniedBeforeAnyVersionQuery() {
        UUID unrelatedTeacher = UUID.randomUUID();

        assertThrows(QuizOwnershipDeniedException.class, () -> service.listOwned(unrelatedTeacher, quizId, 20, null));
        assertThrows(QuizOwnershipDeniedException.class, () -> service.getOwned(unrelatedTeacher, quizId, 1));
        verify(versions, never()).findByQuizIdBeforeVersionNumber(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(versions, never()).findByQuizIdAndVersionNumber(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private QuizVersionSummary summary(int versionNumber) {
        return new QuizVersionSummary(
                UUID.randomUUID(),
                quizId,
                versionNumber,
                "Title " + versionNumber,
                "Description " + versionNumber,
                1,
                NOW.plusSeconds(versionNumber));
    }

    private QuizVersion version(int versionNumber, String source) {
        return new QuizVersion(
                UUID.randomUUID(),
                quizId,
                versionNumber,
                "Historical title",
                "Historical description",
                source,
                new QuizMarkdownParser()
                        .parse("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n")
                        .content()
                        .orElseThrow(),
                1,
                NOW);
    }
}
