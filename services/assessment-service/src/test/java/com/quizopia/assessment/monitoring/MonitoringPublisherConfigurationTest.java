package com.quizopia.assessment.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MonitoringPublisherConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(MonitoringPublisherConfiguration.class)
            .withBean(HikariDataSource.class, HikariDataSource::new);

    @Test
    void defaultOffDoesNotStartWorkerBrokerOrCapturePool() {
        contexts.run(c -> {
            assertNull(c.getStartupFailure());
            assertEquals(
                    "SET quizopia.monitoring_capture='false'",
                    c.getBean(HikariDataSource.class).getConnectionInitSql());
            assertEquals(0, c.getBeansOfType(MonitoringPublisherWorker.class).size());
            assertEquals(
                    0, c.getBeansOfType(AssessmentMonitoringPublisher.class).size());
        });
    }

    @Test
    void explicitlyTypedCaptureEnablesFixedSqlWithoutStartingPublisher() {
        contexts.withPropertyValues("quizopia.assessment.monitoring-events.capture-enabled=true")
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    assertEquals(
                            "SET quizopia.monitoring_capture='true'",
                            c.getBean(HikariDataSource.class).getConnectionInitSql());
                    assertEquals(
                            0, c.getBeansOfType(MonitoringPublisherWorker.class).size());
                });
    }

    @Test
    void conflictingExistingInitializerFailsClosed() {
        new ApplicationContextRunner()
                .withUserConfiguration(MonitoringPublisherConfiguration.class)
                .withPropertyValues("quizopia.assessment.monitoring-events.capture-enabled=true")
                .withBean(HikariDataSource.class, () -> {
                    var d = new HikariDataSource();
                    d.setConnectionInitSql("SET application_name='existing'");
                    return d;
                })
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void disabledCapturePreservesUnrelatedInitializerThenForcesOff() {
        new ApplicationContextRunner()
                .withUserConfiguration(MonitoringPublisherConfiguration.class)
                .withBean(HikariDataSource.class, () -> {
                    var d = new HikariDataSource();
                    d.setConnectionInitSql("SET application_name='existing'");
                    return d;
                })
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    assertEquals(
                            "SET application_name='existing'\n; SET quizopia.monitoring_capture='false'",
                            c.getBean(HikariDataSource.class).getConnectionInitSql());
                });
    }

    @Test
    void productionProfileRejectsEitherFutureFlagAndPlaintextIsOnlyExplicitLoopback() {
        for (String flag : new String[] {"capture-enabled", "publisher-enabled"})
            contexts.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                    .withPropertyValues("quizopia.assessment.monitoring-events." + flag + "=true")
                    .run(c -> assertNotNull(c.getStartupFailure()));
        var p = new MonitoringPublisherProperties();
        p.setPublisherEnabled(true);
        p.setHost("broker.private");
        p.setVirtualHost("monitoring-private");
        p.setUsername("assessment-monitoring");
        p.setPassword("fixture-only");
        p.setAllowInsecureLoopback(true);
        assertThrows(IllegalStateException.class, () -> p.validate(false));
        p.setAllowInsecureLoopback(false);
        assertDoesNotThrow(() -> p.validate(false));
        p.setVirtualHost("/");
        assertThrows(IllegalStateException.class, () -> p.validate(false));
        assertEquals("MonitoringPublisherProperties[redacted]", p.toString());
    }
}
