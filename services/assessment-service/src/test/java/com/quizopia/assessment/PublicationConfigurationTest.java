package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.domain.PublicationConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicationConfigurationTest {
    PublicationConfiguration config(
            int duration,
            Instant start,
            Instant end,
            PublicationConfiguration.Audience audience,
            UUID classroom,
            PublicationConfiguration.MonitoringChoice monitoring) {
        return new PublicationConfiguration(
                "Title",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                audience,
                classroom,
                start,
                end,
                duration,
                monitoring);
    }

    final Instant start = Instant.parse("2026-01-01T00:00:00.123456Z"), end = start.plusSeconds(3600);

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 4, 241, Integer.MAX_VALUE})
    void durationBounds(int duration) {
        assertThrows(
                IllegalArgumentException.class,
                () -> config(
                        duration,
                        start,
                        end,
                        PublicationConfiguration.Audience.PUBLIC,
                        null,
                        PublicationConfiguration.MonitoringChoice.DISABLED));
    }

    @Test
    void chronologicalPrecisionAndAudienceInvariants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> config(
                        5,
                        start,
                        start,
                        PublicationConfiguration.Audience.PUBLIC,
                        null,
                        PublicationConfiguration.MonitoringChoice.DISABLED));
        assertThrows(
                IllegalArgumentException.class,
                () -> config(
                        5,
                        start.plusNanos(1),
                        end,
                        PublicationConfiguration.Audience.PUBLIC,
                        null,
                        PublicationConfiguration.MonitoringChoice.DISABLED));
        assertThrows(
                IllegalArgumentException.class,
                () -> config(
                        5,
                        start,
                        end,
                        PublicationConfiguration.Audience.CLASS,
                        null,
                        PublicationConfiguration.MonitoringChoice.ENABLED));
        assertThrows(
                IllegalArgumentException.class,
                () -> config(
                        5,
                        start,
                        end,
                        PublicationConfiguration.Audience.PUBLIC,
                        UUID.randomUUID(),
                        PublicationConfiguration.MonitoringChoice.DISABLED));
        assertThrows(
                IllegalArgumentException.class,
                () -> config(
                        5,
                        start,
                        end,
                        PublicationConfiguration.Audience.PUBLIC,
                        null,
                        PublicationConfiguration.MonitoringChoice.UNCONFIGURED));
        assertEquals(
                240,
                config(
                                240,
                                start,
                                end,
                                PublicationConfiguration.Audience.CLASS,
                                UUID.randomUUID(),
                                PublicationConfiguration.MonitoringChoice.UNCONFIGURED)
                        .durationMinutes());
        assertFalse(config(
                        5,
                        start,
                        end,
                        PublicationConfiguration.Audience.PUBLIC,
                        null,
                        PublicationConfiguration.MonitoringChoice.DISABLED)
                .toString()
                .contains("Title"));
    }
}
