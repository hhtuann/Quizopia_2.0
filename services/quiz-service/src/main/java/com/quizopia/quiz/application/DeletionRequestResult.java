package com.quizopia.quiz.application;

import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.UUID;

public record DeletionRequestResult(UUID operationId, QuizDeletionState status, boolean created) {}
