package com.quizopia.quiz.application;

/**
 * Mutable draft authoring state supplied to create and update use cases.
 *
 * <p>Ownership and timestamps are deliberately absent because the application service derives them
 * from the authenticated caller and its clock. Authoring source remains opaque and is preserved as
 * supplied.
 */
public record QuizDraftInput(String title, String description, String authoringSource) {}
