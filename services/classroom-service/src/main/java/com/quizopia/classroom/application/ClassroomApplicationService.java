package com.quizopia.classroom.application;

import com.quizopia.classroom.domain.Classroom;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassroomApplicationService {
    private final ClassroomRepository classroomRepository;
    private final ClassroomRecordIdGenerator classroomRecordIdGenerator;

    public ClassroomApplicationService(
            ClassroomRepository classroomRepository, ClassroomRecordIdGenerator classroomRecordIdGenerator) {
        this.classroomRepository = Objects.requireNonNull(classroomRepository, "classroomRepository");
        this.classroomRecordIdGenerator =
                Objects.requireNonNull(classroomRecordIdGenerator, "classroomRecordIdGenerator");
    }

    @Transactional
    public Classroom create(UUID authenticatedCallerUserId, CreateClassroomInput input) {
        Objects.requireNonNull(authenticatedCallerUserId, "authenticatedCallerUserId");
        Objects.requireNonNull(input, "input");

        UUID classroomId = Objects.requireNonNull(
                classroomRecordIdGenerator.generate(), "classroomRecordIdGenerator returned null");
        Classroom classroom = new Classroom(
                classroomId,
                authenticatedCallerUserId,
                input.name(),
                input.subject(),
                input.grade(),
                input.description());
        classroomRepository.insert(classroom);
        return classroom;
    }

    @Transactional(readOnly = true)
    public Classroom getOwnedClassroom(UUID authenticatedCallerUserId, UUID classroomId) {
        Objects.requireNonNull(authenticatedCallerUserId, "authenticatedCallerUserId");
        Objects.requireNonNull(classroomId, "classroomId");

        Classroom classroom = classroomRepository
                .findById(classroomId)
                .orElseThrow(() -> new ClassroomNotFoundException(classroomId));
        if (!classroom.isOwnedBy(authenticatedCallerUserId)) {
            throw new ClassroomOwnershipDeniedException(classroomId, authenticatedCallerUserId);
        }
        return classroom;
    }
}
