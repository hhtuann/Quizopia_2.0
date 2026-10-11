package com.quizopia.identity.configuration;

import com.quizopia.identity.application.admission.UserAuthorityReconciler;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class UserAuthorityReconciliationConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "quizopia.identity.user-admission",
            name = "reconciliation-enabled",
            havingValue = "true")
    AuthorityPoller authorityPoller(
            UserAuthorityReconciler reconciler, MeterRegistry metrics, UserAdmissionProperties properties) {
        return new AuthorityPoller(reconciler, metrics, properties);
    }

    /** Own executor so slow authority recovery cannot occupy the email outbox scheduler. */
    public static final class AuthorityPoller implements AutoCloseable {
        private final UserAuthorityReconciler reconciler;
        private final MeterRegistry metrics;
        private final long intervalMillis;
        private final AtomicBoolean started = new AtomicBoolean();
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "identity-authority-reconciliation");
            thread.setDaemon(true);
            return thread;
        });

        AuthorityPoller(UserAuthorityReconciler reconciler, MeterRegistry metrics, UserAdmissionProperties properties) {
            properties.validate();
            this.reconciler = reconciler;
            this.metrics = metrics;
            this.intervalMillis = properties.getPollingInterval().toMillis();
        }

        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            if (started.compareAndSet(false, true))
                executor.scheduleWithFixedDelay(this::poll, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        public void poll() {
            try {
                reconciler.reconcileBatch();
            } catch (RuntimeException exception) {
                metrics.counter("identity.authority.reconciliation", "outcome", "unavailable")
                        .increment();
            }
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
