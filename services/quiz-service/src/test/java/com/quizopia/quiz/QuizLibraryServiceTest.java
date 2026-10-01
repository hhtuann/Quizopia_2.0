package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quizopia.quiz.application.InvalidQuizLibraryRequestException;
import com.quizopia.quiz.application.QuizLibraryCursorCodec;
import com.quizopia.quiz.application.QuizLibraryItem;
import com.quizopia.quiz.application.QuizLibraryPage;
import com.quizopia.quiz.application.QuizLibraryPosition;
import com.quizopia.quiz.application.QuizLibraryRepository;
import com.quizopia.quiz.application.QuizLibraryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuizLibraryServiceTest {
    private final RecordingRepository repository = new RecordingRepository();
    private final QuizLibraryCursorCodec cursorCodec = new QuizLibraryCursorCodec();
    private final QuizLibraryService service = new QuizLibraryService(repository, cursorCodec);

    @Test
    void firstPageFetchesOneExtraAndReturnsCursorFromLastVisibleItem() {
        UUID owner = UUID.randomUUID();
        QuizLibraryItem first = item("00000000-0000-0000-0000-000000000003", "2026-10-01T03:00:00Z");
        QuizLibraryItem second = item("00000000-0000-0000-0000-000000000002", "2026-10-01T02:00:00Z");
        QuizLibraryItem third = item("00000000-0000-0000-0000-000000000001", "2026-10-01T01:00:00Z");
        repository.results = List.of(first, second, third);

        QuizLibraryPage page = service.listOwned(owner, 2, null);

        assertEquals(List.of(first, second), page.items());
        assertNotNull(page.nextCursor());
        assertEquals(owner, repository.ownerUserId);
        assertNull(repository.before);
        assertEquals(3, repository.fetchLimit);
        assertEquals(
                new QuizLibraryPosition(second.updatedAt(), second.quizId()), cursorCodec.decode(page.nextCursor()));
    }

    @Test
    void finalAndEmptyPagesReturnNullCursor() {
        QuizLibraryItem only = item("00000000-0000-0000-0000-000000000001", "2026-10-01T01:00:00Z");
        repository.results = List.of(only);

        QuizLibraryPage finalPage = service.listOwned(UUID.randomUUID(), 20, null);
        assertEquals(List.of(only), finalPage.items());
        assertNull(finalPage.nextCursor());

        repository.results = List.of();
        QuizLibraryPage empty = service.listOwned(UUID.randomUUID(), 20, null);
        assertEquals(List.of(), empty.items());
        assertNull(empty.nextCursor());
    }

    @Test
    void validCursorIsDecodedAndPassedToRepository() {
        QuizLibraryPosition position = new QuizLibraryPosition(
                Instant.parse("2026-10-01T02:03:04.123456Z"), UUID.fromString("00000000-0000-0000-0000-000000000123"));
        String cursor = cursorCodec.encode(position);
        repository.results = List.of();

        service.listOwned(UUID.randomUUID(), 10, cursor);

        assertEquals(position, repository.before);
        assertEquals(11, repository.fetchLimit);
    }

    @Test
    void malformedCursorFailsBeforeQuery() {
        assertThrows(
                InvalidQuizLibraryRequestException.class,
                () -> service.listOwned(UUID.randomUUID(), 20, "not-a-valid-cursor"));
        assertEquals(0, repository.calls);
    }

    @Test
    void invalidLimitsFailBeforeQueryAndMaxBoundIsAccepted() {
        assertThrows(InvalidQuizLibraryRequestException.class, () -> service.listOwned(UUID.randomUUID(), 0, null));
        assertThrows(InvalidQuizLibraryRequestException.class, () -> service.listOwned(UUID.randomUUID(), -1, null));
        assertThrows(InvalidQuizLibraryRequestException.class, () -> service.listOwned(UUID.randomUUID(), 101, null));
        assertEquals(0, repository.calls);

        repository.results = List.of();
        service.listOwned(UUID.randomUUID(), 100, null);
        assertEquals(101, repository.fetchLimit);
    }

    private static QuizLibraryItem item(String id, String updatedAt) {
        return new QuizLibraryItem(
                UUID.fromString(id),
                "Title",
                null,
                Instant.parse("2026-09-30T00:00:00Z"),
                Instant.parse(updatedAt),
                null);
    }

    private static final class RecordingRepository implements QuizLibraryRepository {
        private List<QuizLibraryItem> results = new ArrayList<>();
        private UUID ownerUserId;
        private QuizLibraryPosition before;
        private int fetchLimit;
        private int calls;

        @Override
        public List<QuizLibraryItem> findOwned(UUID ownerUserId, QuizLibraryPosition before, int fetchLimit) {
            calls++;
            this.ownerUserId = ownerUserId;
            this.before = before;
            this.fetchLimit = fetchLimit;
            return results;
        }
    }
}
