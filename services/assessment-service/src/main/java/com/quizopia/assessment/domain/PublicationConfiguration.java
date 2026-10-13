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
        MonitoringChoice monitoringChoice,
        Integer maxAttempts) {
    public enum Audience {
        CLASS,
        PUBLIC
    }

    public enum MonitoringChoice {
        UNCONFIGURED,
        ENABLED,
        DISABLED
    }

    public PublicationConfiguration(
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
        this(
                title,
                description,
                quizId,
                quizVersionId,
                versionNumber,
                audience,
                classroomId,
                startAt,
                endAt,
                durationMinutes,
                monitoringChoice,
                1);
    }

    public PublicationConfiguration {
        if (maxAttempts == null) maxAttempts = 1; // Missing legacy JSON field, not explicit wire null.

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
                || (durationMinutes != 0 && durationMinutes < 5)
                || durationMinutes > 240
                || maxAttempts < 0
                || maxAttempts > 10
                || (durationMinutes == 0 && monitoringChoice == MonitoringChoice.ENABLED)
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
