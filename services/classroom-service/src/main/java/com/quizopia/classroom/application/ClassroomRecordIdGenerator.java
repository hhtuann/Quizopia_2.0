package com.quizopia.classroom.application;

import java.util.UUID;

/** Generates identifiers for records owned by Classroom Service. */
@FunctionalInterface
public interface ClassroomRecordIdGenerator {
    UUID generate();
}
