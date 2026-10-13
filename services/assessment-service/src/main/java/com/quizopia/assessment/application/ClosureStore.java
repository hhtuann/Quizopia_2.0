package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.*;

/** Local persistence port. Adapters establish fresh USER admission before every call;
 * caller UUIDs alone grant no authority. Network checks execute outside these root transactions. */
public interface ClosureStore {
    record Teacher(UUID id, Instant expiresAt, String evidence) {
        public String toString() {
            return "Teacher[redacted]";
        }
    }

    record Command(UUID publicationId, UUID requestId, long revision, String key, String action) {}

    record Work(UUID publicationId, UUID requestId, UUID attemptId, UUID recipientId, String stage) {}

    record Operation(boolean acknowledged, ClosureViews.Reply reply, int used, int budget) {}

    ClosureViews.Reply request(Teacher actor, Command command);

    ClosureViews.Reply cancel(Teacher actor, Command command);

    ClosureViews.Closure get(Teacher actor, UUID publication, UUID request);

    ClosureViews.Reply preflight(Teacher actor, Command command);

    Optional<UUID> probeRecipient(Teacher actor, UUID publication);

    Operation reserve(Teacher actor, Command command, int budget);

    Optional<UUID> due(UUID publication, UUID request);

    ClosureViews.Reply finish(Teacher actor, Command command);

    ClosureViews.Reply close(Teacher actor, Command command);

    List<Work> candidates(int limit);

    boolean publish(Work work);

    boolean confirm(Work work, ClosureDependencies.Eligibility eligibility);

    ClosureViews.Notification published(Work work);

    ClosureViews.Notification notification(UUID actor, UUID notification);

    ClosureViews.Page<ClosureViews.Notification> inbox(UUID actor, int limit, String cursor);

    ClosureViews.Page<ClosureViews.TeacherRow> results(Teacher actor, UUID publication, int limit, String cursor);

    void workOutcome(Work work, String outcome);

    Map<String, Double> backlog();
}
