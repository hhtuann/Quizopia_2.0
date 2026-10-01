package com.quizopia.quiz.api;

import com.quizopia.quiz.application.QuizLibraryPage;
import java.util.List;

public record QuizLibraryResponse(List<QuizLibraryItemResponse> items, String nextCursor) {
    static QuizLibraryResponse from(QuizLibraryPage page) {
        return new QuizLibraryResponse(
                page.items().stream().map(QuizLibraryItemResponse::from).toList(), page.nextCursor());
    }
}
