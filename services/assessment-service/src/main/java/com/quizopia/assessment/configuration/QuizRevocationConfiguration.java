package com.quizopia.assessment.configuration;

import com.quizopia.assessment.application.QuizRevocationService;
import com.quizopia.assessment.infrastructure.QuizRevocationAckWorker;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FB-015 PR B wiring. The route-level fence gate is always compiled; only the private command
 * surface and this background acknowledger follow quizopia.deletion.enabled.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class QuizRevocationConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "true")
    QuizRevocationAckWorker quizRevocationAckWorker(
            QuizRevocationService service, AssessmentDeletionProperties properties, MeterRegistry metrics) {
        var worker = new QuizRevocationAckWorker(
                service, metrics, properties.ackPollDelaySeconds(), properties.ackBatchSize());
        worker.start();
        return worker;
    }
}
