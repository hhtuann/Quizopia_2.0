package com.quizopia.quiz.application;

import java.util.List;

public record QuizVersionPage(List<QuizVersionSummary> items, String nextCursor) {
    public QuizVersionPage {
        items = List.copyOf(items);
    }
}
