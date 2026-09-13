package com.quizopia.classroom.application;

import com.quizopia.classroom.domain.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Classroom-owned persistence boundary for pending invitations. */
public interface PendingClassroomInvitationRepository {
    void insert(PendingClassroomInvitation value);

    Optional<PendingClassroomInvitation> findByClassroomIdAndEmail(UUID classroomId, NormalizedInvitationEmail email);

    /** Reserves every matching invitation for mutation within the caller's transaction. */
    List<PendingClassroomInvitation> findAllByEmailForClaim(NormalizedInvitationEmail email);

    void deleteById(UUID id);
}
