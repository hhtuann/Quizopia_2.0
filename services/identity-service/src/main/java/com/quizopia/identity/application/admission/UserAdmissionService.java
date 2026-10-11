package com.quizopia.identity.application.admission;

import com.quizopia.identity.infrastructure.admission.RedisUserAuthorityStore;
import com.quizopia.identity.persistence.admission.PrimaryUserAuthorityQuery;
import com.quizopia.identity.persistence.entity.UserRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class UserAdmissionService {
    private final RedisUserAuthorityStore redis;
    private final PrimaryUserAuthorityQuery primary;
    private final MeterRegistry metrics;

    public UserAdmissionService(
            RedisUserAuthorityStore redis, PrimaryUserAuthorityQuery primary, MeterRegistry metrics) {
        this.redis = redis;
        this.primary = primary;
        this.metrics = metrics;
    }

    public AdmissionResponse admit(UUID actor, Instant issuedAt, List<String> tokenRoles) {
        try {
            UserAuthorityFacts projected = redis.read(actor);
            UserAuthorityFacts durable = primary.read(actor);
            if (!projected.equals(durable)) throw AdmissionFailure.unavailable();
            if (!durable.eligible() || durable.revokes(issuedAt)) throw AdmissionFailure.invalidUser();
            // A grant requires a new USER token before use; removed JWT roles never survive current authority.
            List<String> currentRoles =
                    durable.roles().stream().map(UserRole::name).toList();
            if (!currentRoles.equals(tokenRoles.stream().distinct().sorted().toList()))
                throw AdmissionFailure.invalidUser();
            metrics.counter("identity.admission", "outcome", "accepted").increment();
            return new AdmissionResponse(actor, currentRoles);
        } catch (AdmissionFailure failure) {
            metrics.counter("identity.admission", "outcome", failure.code()).increment();
            throw failure;
        }
    }

    public record AdmissionResponse(UUID actorId, List<String> roles) {}
}
