package com.quizopia.identity.application.admission;

import com.quizopia.identity.infrastructure.admission.RedisUserAuthorityStore;
import com.quizopia.identity.persistence.admission.PrimaryUserAuthorityQuery;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class NoticeRecipientEligibility {
    private final java.util.concurrent.Semaphore capacity;
    private final RedisUserAuthorityStore redis;
    private final PrimaryUserAuthorityQuery primary;

    public NoticeRecipientEligibility(
            RedisUserAuthorityStore redis,
            PrimaryUserAuthorityQuery primary,
            com.quizopia.identity.configuration.NoticeRecipientProperties properties) {
        capacity = new java.util.concurrent.Semaphore(properties.maxConcurrent());
        this.redis = redis;
        this.primary = primary;
    }

    public Response check(UUID student) {
        if (!capacity.tryAcquire()) throw AdmissionFailure.unavailable();
        try {
            var projected = redis.read(student);
            var durable = primary.readStamped(student);
            if (!projected.equals(durable.facts())) throw AdmissionFailure.unavailable();
            return new Response(student, durable.facts().eligible(), durable.checkedAt());
        } catch (RuntimeException e) {
            throw AdmissionFailure.unavailable();
        } finally {
            capacity.release();
        }
    }

    public record Response(UUID studentId, boolean eligible, Instant checkedAt) {
        public String toString() {
            return "RecipientEligibility[redacted]";
        }
    }
}
