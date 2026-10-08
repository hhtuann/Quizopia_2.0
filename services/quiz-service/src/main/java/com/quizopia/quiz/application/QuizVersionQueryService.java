package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizVersion;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuizVersionQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final QuizRepository quizRepository;
    private final QuizVersionRepository quizVersionRepository;
    private final QuizVersionCursorCodec cursorCodec;

    public QuizVersionQueryService(
            QuizRepository quizRepository,
            QuizVersionRepository quizVersionRepository,
            QuizVersionCursorCodec cursorCodec) {
        this.quizRepository = Objects.requireNonNull(quizRepository, "quizRepository");
        this.quizVersionRepository = Objects.requireNonNull(quizVersionRepository, "quizVersionRepository");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
    }

    @Transactional(readOnly = true)
    public QuizVersionPage listOwned(UUID callerUserId, UUID quizId, int limit, String cursor) {
        requireOwnedQuiz(callerUserId, quizId);
        validateLimit(limit);

        Integer beforeVersionNumber = cursor == null ? null : cursorCodec.decode(cursor);
        List<QuizVersionSummary> fetched =
                quizVersionRepository.findByQuizIdBeforeVersionNumber(quizId, beforeVersionNumber, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<QuizVersionSummary> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            nextCursor = cursorCodec.encode(items.get(items.size() - 1).versionNumber());
        }
        return new QuizVersionPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public QuizVersion getOwned(UUID callerUserId, UUID quizId, int versionNumber) {
        requireOwnedQuiz(callerUserId, quizId);
        if (versionNumber < 1) {
            throw new InvalidQuizVersionRequestException("Quiz version number must be positive");
        }
        return quizVersionRepository
                .findByQuizIdAndVersionNumber(quizId, versionNumber)
                .orElseThrow(() -> new QuizVersionNotFoundException(quizId, versionNumber));
    }

    private void requireOwnedQuiz(UUID callerUserId, UUID quizId) {
        Objects.requireNonNull(callerUserId, "callerUserId");
        Objects.requireNonNull(quizId, "quizId");
        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new QuizNotFoundException(quizId));
        if (!quiz.isOwnedBy(callerUserId)) {
            throw new QuizOwnershipDeniedException(quizId, callerUserId);
        }
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidQuizVersionRequestException(
                    "Quiz version history limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
