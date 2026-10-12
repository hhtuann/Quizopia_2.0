package com.quizopia.assessment.application;

import com.quizopia.assessment.domain.PublicationConfiguration;
import java.util.UUID;

public interface PublicationSources {
    String snapshot(PublicationConfiguration configuration, String userEvidence);

    void teacher(UUID classroomId, UUID actor, String userEvidence);

    void member(UUID classroomId, UUID actor, String userEvidence);
}
