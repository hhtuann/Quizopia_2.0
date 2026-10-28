package com.quizopia.quiz.api.internal;

import com.quizopia.quiz.application.*;
import com.quizopia.quiz.domain.markdown.QuizContent;
import com.quizopia.quiz.security.internal.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
public class QuizSnapshotController {
    private final QuizVersionQueryService versions;

    public QuizSnapshotController(QuizVersionQueryService versions) {
        this.versions = versions;
    }

    @PostMapping("/internal/quiz-versions/{quizId}/{quizVersionId}/{versionNumber}/snapshot")
    public Snapshot snapshot(
            @PathVariable("quizId") UUID quizId,
            @PathVariable("quizVersionId") UUID quizVersionId,
            @PathVariable("versionNumber") int versionNumber,
            HttpServletRequest request) {
        var version =
                versions.getOwnedForPublicationHandoff(InternalAuthorityFilter.actor(request), quizId, versionNumber);
        if (!version.id().equals(quizVersionId)) throw new QuizVersionNotFoundException(quizId, versionNumber);
        return new Snapshot(
                1,
                version.quizId(),
                version.id(),
                version.versionNumber(),
                version.titleSnapshot(),
                version.descriptionSnapshot(),
                version.sourceSnapshot(),
                version.structuredContent(),
                version.contentSchemaVersion(),
                version.createdAt());
    }

    public record Snapshot(
            int schemaVersion,
            UUID quizId,
            UUID quizVersionId,
            int versionNumber,
            String titleSnapshot,
            String descriptionSnapshot,
            String sourceSnapshot,
            QuizContent structuredContent,
            int contentSchemaVersion,
            Instant createdAt) {
        @Override
        public String toString() {
            return "Snapshot[redacted]";
        }
    }

    @ExceptionHandler({
        QuizNotFoundException.class,
        QuizVersionNotFoundException.class,
        QuizOwnershipDeniedException.class,
        QuizDeletionPendingException.class
    })
    void inaccessible(HttpServletResponse response) throws IOException {
        InternalErrors.write(response, 404, "SOURCE_NOT_ACCESSIBLE");
    }

    @ExceptionHandler({
        org.springframework.dao.DataAccessException.class,
        org.springframework.transaction.TransactionException.class
    })
    void unavailable(HttpServletResponse response) throws IOException {
        InternalErrors.write(response, 503, "AUTHORITY_UNAVAILABLE");
    }
}
