package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDraft;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuizApplicationService {
    private final QuizRepository quizRepository;
    private final QuizDraftRepository quizDraftRepository;
    private final QuizIdGenerator quizIdGenerator;
    private final Clock clock;

    public QuizApplicationService(
            QuizRepository quizRepository,
            QuizDraftRepository quizDraftRepository,
            QuizIdGenerator quizIdGenerator,
            Clock clock) {
        this.quizRepository = Objects.requireNonNull(quizRepository, "quizRepository");
        this.quizDraftRepository = Objects.requireNonNull(quizDraftRepository, "quizDraftRepository");
        this.quizIdGenerator = Objects.requireNonNull(quizIdGenerator, "quizIdGenerator");
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
        Quiz quiz = requireOwnedQuiz(callerUserId, quizId);
        QuizDraft currentDraft = requireDraft(quizId);
        QuizDraft updatedDraft = new QuizDraft(
                currentDraft.quizId(), input.title(), input.description(), input.authoringSource(), clock.instant());
        quizDraftRepository.save(updatedDraft);
        return new QuizDraftDetails(quiz, updatedDraft);
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

    private QuizDraft requireDraft(UUID quizId) {
        return quizDraftRepository.findByQuizId(quizId).orElseThrow(() -> new QuizDraftNotFoundException(quizId));
    }
}
