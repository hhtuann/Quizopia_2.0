package com.quizopia.proctoring.configuration;

import com.quizopia.proctoring.application.QuizPurgeService;
import com.quizopia.proctoring.application.QuizRevocationService;
import com.quizopia.proctoring.infrastructure.QuizRevocationAckWorker;
import com.quizopia.proctoring.media.MediaProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FB-015 PR C wiring. The route-level fence gate is always compiled; only the background
 * acknowledger follows quizopia.deletion.enabled. The purge service always exists (the
 * private endpoint itself is flag-gated), and records media erasure requests only when a
 * media provider is configured - never a fabricated provider receipt.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ProctoringDeletionProperties.class)
public class DeletionConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "true")
    QuizRevocationAckWorker quizRevocationAckWorker(
            QuizRevocationService service, ProctoringDeletionProperties properties, MeterRegistry metrics) {
        var worker = new QuizRevocationAckWorker(
                service, metrics, properties.ackPollDelaySeconds(), properties.ackBatchSize());
        worker.start();
        return worker;
    }

    @Bean
    QuizPurgeService quizPurgeService(
            @org.springframework.context.annotation.Lazy org.springframework.jdbc.core.JdbcTemplate db,
            @org.springframework.context.annotation.Lazy
                    org.springframework.transaction.PlatformTransactionManager manager,
            ObjectProvider<MediaProvider> providers) {
        var provider = providers.getIfAvailable();
        return new QuizPurgeService(db, manager, provider == null ? "" : provider.providerName());
    }
}
