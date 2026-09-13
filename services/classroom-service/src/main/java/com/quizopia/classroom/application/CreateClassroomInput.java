package com.quizopia.classroom.application;

/**
 * Classroom metadata supplied to the create use case.
 *
 * <p>The owner is deliberately absent: the application service derives it from the authenticated
 * caller ID supplied separately by the authentication boundary.
 */
public record CreateClassroomInput(String name, String subject, String grade, String description) {}
