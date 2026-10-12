package com.quizopia.assessment.application;

import com.quizopia.assessment.domain.*;
import java.time.Instant;
import java.util.UUID;

public interface PublicationStore {
    Publication owned(UUID actor, UUID id);

    Publication create(UUID actor, String key, PublicationConfiguration configuration);

    Publication replay(UUID actor, String key, PublicationConfiguration configuration);

    Publication update(UUID actor, UUID id, long revision, PublicationConfiguration configuration);

    Publication open(UUID actor, UUID id, long revision, String snapshot);

    Publication extend(UUID actor, UUID id, long revision, Instant endAt, int durationMinutes);

    Publication publicEntry(String locator);

    Publication classEntry(UUID id);
}
