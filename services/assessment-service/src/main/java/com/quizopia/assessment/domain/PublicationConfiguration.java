package com.quizopia.assessment.domain;

import java.time.Instant;
import java.util.UUID;

public record PublicationConfiguration(
        String title,
        String description,
        UUID quizId,
        UUID quizVersionId,
        int versionNumber,
        Audience audience,
        UUID classroomId,
        Instant startAt,
        Instant endAt,
        int durationMinutes,
        MonitoringChoice monitoringChoice) {
    public enum Audience {
        CLASS,
        PUBLIC
    }

    public enum MonitoringChoice {
        UNCONFIGURED,
        ENABLED,
        DISABLED
    }

    public PublicationConfiguration {
        if (title == null
                || title.isBlank()
                || title.length() > 300
                || (description != null && description.length() > 5000)
                || quizId == null
                || quizVersionId == null
                || versionNumber < 1
                || versionNumber > 999999999
                || audience == null
                || monitoringChoice == null
                || startAt == null
                || endAt == null
                || !startAt.isBefore(endAt)
                || startAt.getNano() % 1000 != 0
                || endAt.getNano() % 1000 != 0
                || durationMinutes < 5
                || durationMinutes > 240
                || (audience == Audience.CLASS && classroomId == null)
                || (audience == Audience.PUBLIC
                        && (classroomId != null || monitoringChoice != MonitoringChoice.DISABLED)))
            throw new IllegalArgumentException("Invalid publication configuration");
    }

    @Override
    public String toString() {
        return "PublicationConfiguration[redacted]";
    }
}
