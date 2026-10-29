package com.quizopia.quiz.configuration;

import com.quizopia.quiz.application.DeletionOperationService;
import com.quizopia.quiz.application.DeletionSagaService;
import com.quizopia.quiz.application.QuizDeletionOutboxRepository;
import com.quizopia.quiz.infrastructure.DeletionDependencyClient;
import com.quizopia.quiz.outbox.DeletionOutboxDispatcher;
import com.quizopia.quiz.outbox.DeletionOutboxPublisher;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({DeletionProperties.class, DeletionDependencyProperties.class})
public class DeletionConfiguration {
    private static final Logger log = LoggerFactory.getLogger(DeletionConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "true")
    DeletionOutboxDispatcher deletionOutboxDispatcher(
            QuizDeletionOutboxRepository outboxRepository, DeletionOutboxPublisher publisher, Clock clock) {
        return new DeletionOutboxDispatcher(outboxRepository, publisher, clock);
    }

    @Bean
    DeletionOutboxPublisher deletionOutboxPublisher(ObjectProvider<DeletionSagaService> sagas) {
        // With the saga enabled the durable command is handed to the idempotent coordinator;
        // dispatcher failures record attempts/backoff exactly like any other publisher.
        return entry -> {
            DeletionSagaService saga = sagas.getIfAvailable();
            if (saga != null) {
                saga.onDeletionCommand(entry.operationId());
                return;
            }
            log.info(
                    "Quiz deletion outbox receipt kind={} operationId={} attempts={}",
                    entry.kind(),
                    entry.operationId(),
                    entry.attempts());
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "true")
    DeletionDependencyClient deletionDependencyClient(DeletionDependencyProperties properties) {
        return new DeletionDependencyClient(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "true")
    DeletionSagaService deletionSagaService(
            com.quizopia.quiz.application.QuizDeletionOperationRepository deletionOperationRepository,
            DeletionOperationService deletionOperationService,
            DeletionDependencyClient dependencies,
            DeletionProperties properties) {
        return new DeletionSagaService(
                deletionOperationRepository,
                deletionOperationService,
                dependencies,
                Math.max(1, properties.getSagaBatchSize()));
    }

    /** Bounded periodic reconciliation; retries outages and holds without operator action. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "true")
    static class DeletionSagaPoller {
        private final DeletionSagaService saga;

        DeletionSagaPoller(DeletionSagaService saga) {
            this.saga = Objects.requireNonNull(saga, "saga");
        }

        @Scheduled(initialDelay = 8000, fixedDelay = 5000)
        void reconcile() {
            saga.reconcileDue();
        }
    }
}
