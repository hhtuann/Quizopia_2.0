package com.quizopia.quiz.application;

import java.util.List;

public record QuizLibraryPage(List<QuizLibraryItem> items, String nextCursor) {
    public QuizLibraryPage {
        items = List.copyOf(items);
    }
}
