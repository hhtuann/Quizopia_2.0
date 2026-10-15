package com.quizopia.identity.application.admission;

import com.quizopia.identity.configuration.ProctoringStudentProperties;
import com.quizopia.identity.infrastructure.admission.RedisUserAuthorityStore;
import com.quizopia.identity.persistence.admission.PrimaryUserAuthorityQuery;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Current facts plus a conservative Attempt-start revocation fence; this never authenticates a Student. */
@Service
@Profile("!test")
public class ProctoringStudentEligibility {
    private final Semaphore capacity;
    private final RedisUserAuthorityStore redis;
    private final PrimaryUserAuthorityQuery primary;

    public ProctoringStudentEligibility(
            RedisUserAuthorityStore redis, PrimaryUserAuthorityQuery primary, ProctoringStudentProperties properties) {
        this.capacity = new Semaphore(properties.maxConcurrent());
        this.redis = redis;
        this.primary = primary;
    }

    public Response check(UUID student, Instant attemptStartedAt) {
        if (!capacity.tryAcquire()) throw AdmissionFailure.unavailable();
        try {
            var projected = redis.read(student);
            var durable = primary.readStamped(student);
            if (!projected.equals(durable.facts())) throw AdmissionFailure.unavailable();
            return new Response(
                    student,
                    attemptStartedAt,
                    durable.facts().eligible() && !durable.facts().revokes(attemptStartedAt),
                    durable.checkedAt());
        } catch (RuntimeException failure) {
            throw AdmissionFailure.unavailable();
        } finally {
            capacity.release();
        }
    }

    public record Response(UUID studentId, Instant attemptStartedAt, boolean eligible, Instant checkedAt) {
        @Override
        public String toString() {
            return "ProctoringStudentEligibility[redacted]";
        }
    }
}
