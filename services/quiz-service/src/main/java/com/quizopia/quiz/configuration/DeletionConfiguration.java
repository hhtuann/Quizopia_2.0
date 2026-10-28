package com.quizopia.quiz.configuration;

import com.quizopia.quiz.application.QuizDeletionOutboxRepository;
import com.quizopia.quiz.outbox.DeletionOutboxDispatcher;
import com.quizopia.quiz.outbox.DeletionOutboxPublisher;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(DeletionProperties.class)
public class DeletionConfiguration {
    private static final Logger log = LoggerFactory.getLogger(DeletionConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "true")
    DeletionOutboxDispatcher deletionOutboxDispatcher(
            QuizDeletionOutboxRepository outboxRepository, DeletionOutboxPublisher publisher, Clock clock) {
        return new DeletionOutboxDispatcher(outboxRepository, publisher, clock);
    }

    @Bean
    DeletionOutboxPublisher deletionOutboxPublisher() {
        return entry -> log.info(
                "Quiz deletion outbox receipt kind={} operationId={} attempts={}",
                entry.kind(),
                entry.operationId(),
                entry.attempts());
    }
}
