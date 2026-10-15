package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProctoringAuthorityServiceTest {
    @Test
    void criticalBindingChangesDuringTargetChecksFailClosed() {
        UUID attempt = UUID.randomUUID(),
                publication = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                student = UUID.randomUUID(),
                teacher = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(1), deadline = start.plusSeconds(300);
        var first = new ProctoringAuthorityStore.Projection(
                attempt, publication, classroom, student, teacher, true, true, 5, start, deadline, Instant.now());
        List<ProctoringAuthorityStore.Projection> changes = List.of(
                new ProctoringAuthorityStore.Projection(
                        attempt,
                        UUID.randomUUID(),
                        classroom,
                        student,
                        teacher,
                        true,
                        true,
                        5,
                        start,
                        deadline,
                        Instant.now()),
                new ProctoringAuthorityStore.Projection(
                        attempt,
                        publication,
                        UUID.randomUUID(),
                        student,
                        teacher,
                        true,
                        true,
                        5,
                        start,
                        deadline,
                        Instant.now()),
                new ProctoringAuthorityStore.Projection(
                        attempt,
                        publication,
                        classroom,
                        UUID.randomUUID(),
                        teacher,
                        true,
                        true,
                        5,
                        start,
                        deadline,
                        Instant.now()),
                new ProctoringAuthorityStore.Projection(
                        attempt,
                        publication,
                        classroom,
                        student,
                        teacher,
                        true,
                        true,
                        6,
                        start,
                        deadline,
                        Instant.now()),
                new ProctoringAuthorityStore.Projection(
                        attempt,
                        publication,
                        classroom,
                        student,
                        teacher,
                        true,
                        true,
                        5,
                        start.minusSeconds(1),
                        deadline,
                        Instant.now()),
                new ProctoringAuthorityStore.Projection(
                        attempt,
                        publication,
                        classroom,
                        student,
                        teacher,
                        true,
                        true,
                        5,
                        start,
                        deadline.plusSeconds(1),
                        Instant.now()));
        for (var current : changes) {
            var store = mock(ProctoringAuthorityStore.class);
            var target = mock(ProctoringTargetEligibility.class);
            when(store.find(attempt)).thenReturn(first, current);
            var service = new ProctoringAuthorityService(store, mock(PublicationSources.class), target);
            var failure = assertThrows(
                    PublicationFailure.class,
                    () -> service.authority(
                            teacher,
                            "TEACHER",
                            attempt,
                            "synthetic-teacher-evidence",
                            ProctoringAuthorityService.Purpose.LIVE_MEDIA));
            assertEquals(503, failure.status());
            verify(target).require(classroom, student, start, "synthetic-teacher-evidence");
        }
    }
}
