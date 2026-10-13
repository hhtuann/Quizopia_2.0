package com.quizopia.assessment.application;

import com.quizopia.assessment.domain.PublicationConfiguration;
import java.time.Instant;
import java.util.*;

/** Local root transactions only. Due markers must be reconciled outside these transactions. */
public interface StudentAttemptStore {
    record Actor(UUID id, Instant expiresAt) {
        public Actor {
            Objects.requireNonNull(id);
            Objects.requireNonNull(expiresAt);
        }
    }

    record Resource(UUID publicationId, PublicationConfiguration configuration) {}

    record Step<T>(T value, UUID dueAttempt, boolean created) {
        public static <T> Step<T> done(T value) {
            return new Step<>(value, null, false);
        }

        public static <T> Step<T> due(UUID id) {
            return new Step<>(null, id, false);
        }
    }

    Resource publication(UUID id);

    Resource owned(Actor actor, UUID attemptId);

    Step<AttemptViews.View> start(Actor actor, UUID publication, String action, String locator, String keyDigest);

    Step<AttemptViews.View> resume(Actor actor, UUID id);

    Step<AttemptViews.SaveAck> save(
            Actor actor, UUID id, int question, long revision, String keyDigest, String answerJson);

    Step<AttemptViews.AbandonAck> abandon(Actor actor, UUID id, long revision, String keyDigest);

    Step<AttemptViews.Result> result(Actor actor, UUID id);

    Step<AttemptViews.Aggregate> aggregate(Actor actor, UUID publication);

    Step<AttemptViews.Review> review(Actor actor, UUID id);
}
