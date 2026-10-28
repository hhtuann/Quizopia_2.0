package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuizLibraryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final QuizLibraryRepository repository;
    private final QuizLibraryCursorCodec cursorCodec;
    private final QuizDeletionOperationRepository deletionOperationRepository;

    public QuizLibraryService(
            QuizLibraryRepository repository,
            QuizLibraryCursorCodec cursorCodec,
            QuizDeletionOperationRepository deletionOperationRepository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
        this.deletionOperationRepository =
                Objects.requireNonNull(deletionOperationRepository, "deletionOperationRepository");
    }

    @Transactional(readOnly = true)
    public QuizLibraryPage listOwned(UUID ownerUserId, int limit, String cursor) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        validateLimit(limit);

        QuizLibraryPosition before = cursor == null ? null : cursorCodec.decode(cursor);
        List<QuizLibraryItem> fetched = repository.findOwned(ownerUserId, before, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<QuizLibraryItem> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        Map<UUID, QuizDeletionState> deletionStatuses = deletionOperationRepository.findLatestStatusesByQuizIdIn(
                items.stream().map(item -> item.quizId()).toList());
        List<QuizLibraryItem> enriched = items.stream()
                .map(item -> withDeletionStatus(item, deletionStatuses))
                .toList();
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            QuizLibraryItem last = items.get(items.size() - 1);
            nextCursor = cursorCodec.encode(new QuizLibraryPosition(last.updatedAt(), last.quizId()));
        }
        return new QuizLibraryPage(enriched, nextCursor);
    }

    private static QuizLibraryItem withDeletionStatus(QuizLibraryItem item, Map<UUID, QuizDeletionState> statuses) {
        QuizDeletionState deletionStatus = statuses.get(item.quizId());
        if (deletionStatus == null) {
            return item;
        }
        return new QuizLibraryItem(
                item.quizId(),
                item.title(),
                item.description(),
                item.createdAt(),
                item.updatedAt(),
                item.latestVersionNumber(),
                deletionStatus);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidQuizLibraryRequestException("Quiz library limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
