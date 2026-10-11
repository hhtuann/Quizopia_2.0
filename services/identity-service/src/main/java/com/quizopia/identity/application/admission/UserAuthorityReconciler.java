package com.quizopia.identity.application.admission;

import com.quizopia.identity.infrastructure.admission.RedisUserAuthorityStore;
import com.quizopia.identity.persistence.admission.PrimaryUserAuthorityQuery;
import com.quizopia.identity.persistence.admission.UserAuthorityWorkStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class UserAuthorityReconciler {
    private final PrimaryUserAuthorityQuery primary;
    private final RedisUserAuthorityStore redis;
    private final UserAuthorityWorkStore work;
    private final MeterRegistry metrics;
    private UUID cursor;

    public UserAuthorityReconciler(
            PrimaryUserAuthorityQuery primary,
            RedisUserAuthorityStore redis,
            UserAuthorityWorkStore work,
            MeterRegistry metrics) {
        this.primary = primary;
        this.redis = redis;
        this.work = work;
        this.metrics = metrics;
    }
    /** Independent of requests. Retry-safe; unfinished work survives crash without any lease. */
    public synchronized void reconcileBatch() {
        for (var item : work.pending()) {
            if (Thread.currentThread().isInterrupted()) return;
            if (publish(item.userId())) work.acknowledge(item);
        }
        var actors = work.scanAfter(cursor);
        for (var actor : actors) {
            if (Thread.currentThread().isInterrupted()) return;
            if (!publish(actor)) return; // retry this sweep segment on next poll
            cursor = actor;
        }
        if (actors.isEmpty()) cursor = null;
    }

    private boolean publish(UUID actor) {
        try {
            redis.publish(primary.read(actor));
            metrics.counter("identity.authority.publication", "outcome", "published")
                    .increment();
            return true;
        } catch (RuntimeException exception) {
            metrics.counter("identity.authority.publication", "outcome", "unavailable")
                    .increment();
            return false;
        }
    }
}
