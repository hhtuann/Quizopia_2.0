package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.proctoring.application.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonitoringLookupServiceTest {
    final MonitoringLookup lookup = mock(MonitoringLookup.class);
    final MonitoringStore store = mock(MonitoringStore.class);
    final MonitoringAuthority authority = mock(MonitoringAuthority.class);
    final MonitoringLookupService service = new MonitoringLookupService(lookup, store, authority);
    final UUID attempt = UUID.randomUUID();
    final MonitoringAuthority.Actor teacher = new MonitoringAuthority.Actor(
            UUID.randomUUID(),
            Set.of("STUDENT", "TEACHER"),
            "synthetic-user-evidence",
            Instant.now().plusSeconds(60));

    @Test
    void deniedResourceAuthorityCannotQueryLocalExistence() {
        when(authority.check(teacher, attempt, "TEACHER"))
                .thenThrow(new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE"));
        assertEquals(
                404,
                assertThrows(MonitoringFailure.class, () -> service.byAttempt(teacher, attempt))
                        .status());
        verifyNoInteractions(lookup, store);
    }

    @Test
    void missingTeacherRoleCannotReachAuthorityOrStorage() {
        var student = new MonitoringAuthority.Actor(teacher.id(), Set.of("STUDENT"), "synthetic", teacher.expiresAt());
        assertEquals(
                403,
                assertThrows(MonitoringFailure.class, () -> service.byAttempt(student, attempt))
                        .status());
        verifyNoInteractions(authority, lookup, store);
    }

    @Test
    void mismatchedStaleAndWrongRoleSnapshotsCannotRevealLocalExistence() {
        for (var snapshot : List.of(
                snapshot(UUID.randomUUID(), "TEACHER", attempt, Instant.now()),
                snapshot(teacher.id(), "STUDENT", attempt, Instant.now()),
                snapshot(teacher.id(), "TEACHER", UUID.randomUUID(), Instant.now()),
                snapshot(teacher.id(), "TEACHER", attempt, Instant.now().minusSeconds(10)))) {
            when(authority.check(teacher, attempt, "TEACHER")).thenReturn(snapshot);
            assertEquals(
                    503,
                    assertThrows(MonitoringFailure.class, () -> service.byAttempt(teacher, attempt))
                            .status());
        }
        verifyNoInteractions(lookup, store);
    }

    MonitoringAuthority.Snapshot snapshot(UUID actor, String role, UUID requested, Instant checked) {
        return new MonitoringAuthority.Snapshot(
                actor,
                role,
                requested,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                true,
                Instant.now().plusSeconds(600),
                checked);
    }
}
