package com.quizopia.assessment.realtime;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** W4 invalidation infrastructure; fully independent from the W3 monitoring outbox. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeInvalidationProperties.class)
public class RealtimeInvalidationConfiguration {
    @Bean
    static BeanPostProcessor realtimeInvalidationCaptureConnections(Environment environment) {
        boolean capture = environment.getProperty(
                "quizopia.assessment.realtime-invalidation.capture-enabled", Boolean.class, false);
        boolean publish = environment.getProperty(
                "quizopia.assessment.realtime-invalidation.publisher-enabled", Boolean.class, false);
        if (environment.matchesProfiles("prod") && (capture || publish))
            throw new IllegalStateException("Realtime invalidation activation is pending acceptance");
        return new RealtimeInvalidationCapturePostProcessor(capture);
    }

    static final class RealtimeInvalidationCapturePostProcessor implements BeanPostProcessor, PriorityOrdered {
        private final boolean capture;

        RealtimeInvalidationCapturePostProcessor(boolean capture) {
            this.capture = capture;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String name) {
            if (bean instanceof HikariDataSource datasource) {
                String initial = datasource.getConnectionInitSql();
                if (initial != null
                        && initial.toLowerCase(java.util.Locale.ROOT).contains("quizopia.realtime_invalidation"))
                    throw new IllegalStateException("Realtime invalidation capture SQL is reserved");
                if (datasource.isRunning())
                    throw new IllegalStateException("Realtime invalidation capture requires an uninitialized pool");
                // Compose after unrelated initialization SQL; the final fixed assignment always wins
                // over inherited database/role/connection startup GUCs on every fresh connection.
                datasource.setConnectionInitSql((initial == null || initial.isBlank() ? "" : initial + "\n; ")
                        + "SET quizopia.realtime_invalidation='" + (capture ? "true" : "false") + "'");
            }
            return bean;
        }

        @Override
        public int getOrder() {
            // Run after unrelated pool post-processors (e.g. the W3 monitoring capture) so both
            // fixed assignments compose instead of rejecting each other's initialization line.
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    @Bean
    @ConditionalOnProperty(name = "quizopia.assessment.realtime-invalidation.publisher-enabled", havingValue = "true")
    RealtimeInvalidationOutbox realtimeInvalidationOutbox(JdbcTemplate db, PlatformTransactionManager manager) {
        return new JdbcRealtimeInvalidationOutbox(db, manager);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.assessment.realtime-invalidation.publisher-enabled", havingValue = "true")
    RealtimeInvalidationPublisher realtimeInvalidationPublisher(
            RealtimeInvalidationProperties properties, Environment environment) {
        properties.validate(environment.matchesProfiles("prod"));
        return new RealtimeInvalidationPublisher(properties);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.assessment.realtime-invalidation.publisher-enabled", havingValue = "true")
    RealtimeInvalidationPublisherWorker realtimeInvalidationPublisherWorker(
            RealtimeInvalidationOutbox store,
            RealtimeInvalidationPublisher publisher,
            RealtimeInvalidationProperties properties,
            MeterRegistry metrics) {
        var worker = new RealtimeInvalidationPublisherWorker(store, publisher, properties, metrics);
        Gauge.builder("quizopia.assessment.realtime-invalidation.pending", store, value -> value.pending()
                        .count())
                .register(metrics);
        Gauge.builder(
                        "quizopia.assessment.realtime-invalidation.oldest-pending-seconds",
                        store,
                        value -> value.pending().oldestPendingSeconds())
                .register(metrics);
        return worker;
    }
}
