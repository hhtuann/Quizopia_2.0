package com.quizopia.classroom.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassroomAuthorityService {
    private final ClassroomApplicationService classrooms;
    private final ClassroomMembershipRepository memberships;

    public ClassroomAuthorityService(
            ClassroomApplicationService classrooms, ClassroomMembershipRepository memberships) {
        this.classrooms = classrooms;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public void teacher(UUID actor, UUID classroomId) {
        classrooms.getOwnedClassroom(actor, classroomId);
    }

    @Transactional(readOnly = true)
    public void member(UUID actor, UUID classroomId) {
        if (memberships.findByClassroomIdAndUserId(classroomId, actor).isEmpty())
            throw new ClassroomNotFoundException(classroomId);
    }
}
