package com.quizopia.classroom.application;

import com.quizopia.classroom.domain.*;
import java.util.Optional;
import java.util.UUID;

/** Classroom-owned persistence boundary. Insert rejects existing IDs or unique keys. */
public interface ClassroomRepository {
    void insert(Classroom value);

    Optional<Classroom> findById(UUID id);
}
