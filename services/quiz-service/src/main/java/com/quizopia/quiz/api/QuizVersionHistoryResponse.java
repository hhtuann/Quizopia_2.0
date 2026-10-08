package com.quizopia.quiz.api;

import com.quizopia.quiz.application.QuizVersionPage;
import java.util.List;

public record QuizVersionHistoryResponse(List<QuizVersionSummaryResponse> items, String nextCursor) {
    static QuizVersionHistoryResponse from(QuizVersionPage page) {
        return new QuizVersionHistoryResponse(
                page.items().stream().map(QuizVersionSummaryResponse::from).toList(), page.nextCursor());
    }
}
