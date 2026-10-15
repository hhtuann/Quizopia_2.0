package com.quizopia.assessment.monitoring;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringPublisherProperties.class)
public class MonitoringPublisherConfiguration {
    @Bean
    static BeanPostProcessor monitoringCaptureConnections(Environment environment) {
        boolean capture =
                environment.getProperty("quizopia.assessment.monitoring-events.capture-enabled", Boolean.class, false);
        boolean publish = environment.getProperty(
                "quizopia.assessment.monitoring-events.publisher-enabled", Boolean.class, false);
        if (environment.matchesProfiles("prod") && (capture || publish))
            throw new IllegalStateException("Monitoring event privacy acceptance is pending");
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof HikariDataSource datasource) {
                    String initial = datasource.getConnectionInitSql();
                    if (initial != null
                            && initial.toLowerCase(java.util.Locale.ROOT).contains("quizopia.monitoring_capture"))
                        throw new IllegalStateException("Monitoring capture SQL is reserved");
                    if (datasource.isRunning())
                        throw new IllegalStateException("Monitoring capture requires an uninitialized dedicated pool");
                    if (capture) {
                        if (initial != null && !initial.isBlank())
                            throw new IllegalStateException(
                                    "Monitoring capture requires an uninitialized dedicated pool");
                        datasource.setConnectionInitSql("SET quizopia.monitoring_capture='true'");
                    } else {
                        // Override inherited database/role/connection startup GUCs on every fresh
                        // connection. Preserve unrelated initialization before the fixed OFF assignment.
                        datasource.setConnectionInitSql((initial == null || initial.isBlank() ? "" : initial + "\n; ")
                                + "SET quizopia.monitoring_capture='false'");
                    }
                }
                return bean;
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "quizopia.assessment.monitoring-events.publisher-enabled", havingValue = "true")
    MonitoringOutbox monitoringOutbox(JdbcTemplate db, PlatformTransactionManager manager) {
        return new JdbcMonitoringOutbox(db, manager);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.assessment.monitoring-events.publisher-enabled", havingValue = "true")
    AssessmentMonitoringPublisher assessmentMonitoringPublisher(
            MonitoringPublisherProperties properties, Environment environment) {
        properties.validate(environment.matchesProfiles("prod"));
        return new AssessmentMonitoringPublisher(properties);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.assessment.monitoring-events.publisher-enabled", havingValue = "true")
    MonitoringPublisherWorker monitoringPublisherWorker(
            MonitoringOutbox store, AssessmentMonitoringPublisher publisher, MeterRegistry metrics) {
        return new MonitoringPublisherWorker(store, publisher, metrics);
    }
}
