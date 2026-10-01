package com.quizopia.quiz.application;

import java.time.Instant;
import java.util.UUID;

public record QuizLibraryPosition(Instant updatedAt, UUID quizId) {}
