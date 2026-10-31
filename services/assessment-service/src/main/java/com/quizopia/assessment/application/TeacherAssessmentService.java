package com.quizopia.assessment.application;

import com.quizopia.assessment.application.TeacherAssessmentViews.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Remote current CLASS authority precedes local reads; names are one post-authorized page batch. */
@Service
public class TeacherAssessmentService {
    private final TeacherAssessmentStore queries;
    private final PublicationSources sources;
    private final StudentProfiles profiles;

    public TeacherAssessmentService(
            TeacherAssessmentStore queries, PublicationSources sources, StudentProfiles profiles) {
        this.queries = queries;
        this.sources = sources;
        this.profiles = profiles;
    }

    private <T> T authorized(ClosureStore.Teacher actor, UUID publication, Supplier<T> work) {
        var resource = queries.resource(actor, publication);
        long until = System.nanoTime() + 5_000_000_000L;
        if (resource.classroomId() != null) sources.teacher(resource.classroomId(), actor.id(), actor.evidence());
        // No remote I/O while holding database locks. Bound remote authorization sample to five seconds.
        return queries.withAuthorityDeadline(until, resource, work);
    }

    public Overview overview(ClosureStore.Teacher actor, UUID publication) {
        return authorized(actor, publication, () -> queries.overview(actor, publication));
    }

    public Students students(ClosureStore.Teacher actor, UUID publication, TeacherAssessmentStore.Filter filter) {
        var page = authorized(actor, publication, () -> queries.students(actor, publication, filter));
        var ids = new LinkedHashSet<UUID>();
        page.items().forEach(row -> ids.add(row.studentId()));
        if (ids.isEmpty()) return page;
        var names = profiles.displayNames(ids);
        return new Students(
                publication,
                page.asOf(),
                page.items().stream()
                        .map(row -> new Student(
                                row.studentId(),
                                names.get(row.studentId()),
                                row.attemptCount(),
                                row.gradedAttemptCount(),
                                row.hasActive(),
                                row.hasSubmitted(),
                                row.latestAttempt(),
                                row.latestGradedResult(),
                                row.bestResult(),
                                row.averageResult()))
                        .toList(),
                page.nextCursor());
    }

    public History history(ClosureStore.Teacher actor, UUID publication, UUID student, int limit, String cursor) {
        return authorized(actor, publication, () -> queries.history(actor, publication, student, limit, cursor));
    }

    public Review review(ClosureStore.Teacher actor, UUID publication, UUID attempt) {
        return authorized(actor, publication, () -> queries.review(actor, publication, attempt));
    }
}
