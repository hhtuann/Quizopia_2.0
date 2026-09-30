package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDraft;
import com.quizopia.quiz.domain.QuizVersion;
import com.quizopia.quiz.domain.markdown.QuizContent;
import com.quizopia.quiz.domain.markdown.QuizMarkdownParseResult;
import com.quizopia.quiz.domain.markdown.QuizMarkdownParser;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuizApplicationService {
    private final QuizRepository quizRepository;
    private final QuizDraftRepository quizDraftRepository;
    private final QuizVersionRepository quizVersionRepository;
    private final QuizIdGenerator quizIdGenerator;
    private final QuizVersionIdGenerator quizVersionIdGenerator;
    private final QuizMarkdownParser quizMarkdownParser;
    private final Clock clock;

    public QuizApplicationService(
            QuizRepository quizRepository,
            QuizDraftRepository quizDraftRepository,
            QuizVersionRepository quizVersionRepository,
            QuizIdGenerator quizIdGenerator,
            QuizVersionIdGenerator quizVersionIdGenerator,
            QuizMarkdownParser quizMarkdownParser,
            Clock clock) {
        this.quizRepository = Objects.requireNonNull(quizRepository, "quizRepository");
        this.quizDraftRepository = Objects.requireNonNull(quizDraftRepository, "quizDraftRepository");
        this.quizVersionRepository = Objects.requireNonNull(quizVersionRepository, "quizVersionRepository");
        this.quizIdGenerator = Objects.requireNonNull(quizIdGenerator, "quizIdGenerator");
        this.quizVersionIdGenerator = Objects.requireNonNull(quizVersionIdGenerator, "quizVersionIdGenerator");
        this.quizMarkdownParser = Objects.requireNonNull(quizMarkdownParser, "quizMarkdownParser");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public QuizDraftDetails create(UUID callerUserId, QuizDraftInput input) {
        Objects.requireNonNull(callerUserId, "callerUserId");
        Objects.requireNonNull(input, "input");

        UUID quizId = Objects.requireNonNull(quizIdGenerator.generate(), "quizIdGenerator returned null");
        Instant now = clock.instant();
        Quiz quiz = new Quiz(quizId, callerUserId, now);
        QuizDraft draft = new QuizDraft(quizId, input.title(), input.description(), input.authoringSource(), now);

        quizRepository.insert(quiz);
        quizDraftRepository.save(draft);
        return new QuizDraftDetails(quiz, draft);
    }

    @Transactional(readOnly = true)
    public QuizDraftDetails getOwnedDraft(UUID callerUserId, UUID quizId) {
        Quiz quiz = requireOwnedQuiz(callerUserId, quizId);
        return new QuizDraftDetails(quiz, requireDraft(quizId));
    }

    @Transactional
    public QuizDraftDetails updateOwnedDraft(UUID callerUserId, UUID quizId, QuizDraftInput input) {
        Objects.requireNonNull(input, "input");
        Quiz quiz = requireOwnedQuizForUpdate(callerUserId, quizId);
        QuizDraft currentDraft = requireDraft(quizId);
        QuizDraft updatedDraft = new QuizDraft(
                currentDraft.quizId(), input.title(), input.description(), input.authoringSource(), clock.instant());
        quizDraftRepository.save(updatedDraft);
        return new QuizDraftDetails(quiz, updatedDraft);
    }

    @Transactional
    public QuizPublishResult publishOwnedDraft(UUID callerUserId, UUID quizId) {
        requireOwnedQuizForUpdate(callerUserId, quizId);
        QuizDraft draft = requireDraft(quizId);

        QuizMarkdownParseResult parseResult = quizMarkdownParser.parse(draft.authoringSource());
        if (!parseResult.isValid()) {
            throw new QuizMarkdownInvalidException(parseResult.errors());
        }
        QuizContent structuredContent = parseResult.content().orElseThrow();

        var latest = quizVersionRepository.findLatestByQuizId(quizId);
        if (latest.isPresent() && hasSameSnapshot(latest.get(), draft)) {
            return new QuizPublishResult(latest.get(), false);
        }

        int nextVersionNumber =
                latest.map(version -> version.versionNumber() + 1).orElse(1);
        QuizVersion version = new QuizVersion(
                Objects.requireNonNull(quizVersionIdGenerator.generate(), "quizVersionIdGenerator returned null"),
                quizId,
                nextVersionNumber,
                draft.title(),
                draft.description(),
                draft.authoringSource(),
                structuredContent,
                QuizVersion.CURRENT_CONTENT_SCHEMA_VERSION,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        quizVersionRepository.insert(version);
        return new QuizPublishResult(version, true);
    }

    private Quiz requireOwnedQuiz(UUID callerUserId, UUID quizId) {
        Objects.requireNonNull(callerUserId, "callerUserId");
        Objects.requireNonNull(quizId, "quizId");

        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new QuizNotFoundException(quizId));
        if (!quiz.isOwnedBy(callerUserId)) {
            throw new QuizOwnershipDeniedException(quizId, callerUserId);
        }
        return quiz;
    }

    private Quiz requireOwnedQuizForUpdate(UUID callerUserId, UUID quizId) {
        Objects.requireNonNull(callerUserId, "callerUserId");
        Objects.requireNonNull(quizId, "quizId");

        Quiz quiz = quizRepository.findByIdForUpdate(quizId).orElseThrow(() -> new QuizNotFoundException(quizId));
        if (!quiz.isOwnedBy(callerUserId)) {
            throw new QuizOwnershipDeniedException(quizId, callerUserId);
        }
        return quiz;
    }

    private static boolean hasSameSnapshot(QuizVersion version, QuizDraft draft) {
        return Objects.equals(version.titleSnapshot(), draft.title())
                && Objects.equals(version.descriptionSnapshot(), draft.description())
                && Objects.equals(version.sourceSnapshot(), draft.authoringSource());
    }

    private QuizDraft requireDraft(UUID quizId) {
        return quizDraftRepository.findByQuizId(quizId).orElseThrow(() -> new QuizDraftNotFoundException(quizId));
    }
}
