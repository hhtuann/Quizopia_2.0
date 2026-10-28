package com.quizopia.quiz.api;

import com.quizopia.quiz.application.QuizNotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "false", matchIfMissing = true)
public final class QuizDeletionUnavailableController {

    @PostMapping("/api/quizzes/{quizId}/deletion-requests")
    public void deletionRequestsUnavailable(@PathVariable("quizId") UUID quizId) {
        throw new QuizNotFoundException(quizId);
    }

    @GetMapping("/api/quizzes/{quizId}/deletion-requests/{operationId}")
    public void deletionStatusUnavailable(
            @PathVariable("quizId") UUID quizId, @PathVariable("operationId") UUID operationId) {
        throw new QuizNotFoundException(quizId);
    }
}
