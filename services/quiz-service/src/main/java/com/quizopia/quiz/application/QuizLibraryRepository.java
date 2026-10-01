package com.quizopia.quiz.application;

import java.util.List;
import java.util.UUID;

public interface QuizLibraryRepository {
    List<QuizLibraryItem> findOwned(UUID ownerUserId, QuizLibraryPosition before, int fetchLimit);
}
