package com.quizopia.proctoring.integration;

import com.quizopia.proctoring.configuration.MonitoringProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssessmentEventsProperties.class)
@ConditionalOnProperty(name = "quizopia.proctoring.assessment-events.enabled", havingValue = "true")
public class AssessmentEventsConfiguration {
    @Bean
    AssessmentEventInbox assessmentEventInbox(
            JdbcTemplate db, PlatformTransactionManager manager, MonitoringProperties p) {
        return new AssessmentEventInbox(db, manager, p);
    }

    @Bean
    AssessmentEventConsumer assessmentEventConsumer(
            AssessmentEventInbox inbox,
            AssessmentEventsProperties p,
            MonitoringProperties monitoring,
            Environment env,
            MeterRegistry meters) {
        p.validate(env.matchesProfiles("prod"));
        if (!monitoring.isEnabled()) throw new IllegalStateException("Events require durable monitoring");
        return new AssessmentEventConsumer(inbox, p, meters);
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService assessmentEventsExecutor(AssessmentEventConsumer consumer) {
        var executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "proctoring-assessment-events");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(consumer::poll, 1000, 1000, TimeUnit.MILLISECONDS);
        return executor;
    }
}
