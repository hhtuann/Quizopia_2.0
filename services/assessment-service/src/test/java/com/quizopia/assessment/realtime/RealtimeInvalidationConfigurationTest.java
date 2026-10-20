package com.quizopia.assessment.realtime;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RealtimeInvalidationConfigurationTest {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(RealtimeInvalidationConfiguration.class);
    }

    HikariDataSource pool() {
        var data = new HikariDataSource();
        data.setJdbcUrl("jdbc:postgresql://127.0.0.1:5432/never_connected");
        return data;
    }

    @Test
    void defaultOffForcesTheCaptureGucOffAndNeverEnablesInfrastructure() {
        runner().withBean(HikariDataSource.class, this::pool).run(context -> {
            assertEquals(
                    "SET quizopia.realtime_invalidation='false'",
                    context.getBean(HikariDataSource.class).getConnectionInitSql());
            assertFalse(context.containsBean("realtimeInvalidationPublisher"));
            assertFalse(context.containsBean("realtimeInvalidationPublisherWorker"));
        });
    }

    @Test
    void captureEnabledComposesAfterUnrelatedInitializationSql() {
        runner().withPropertyValues("quizopia.assessment.realtime-invalidation.capture-enabled=true")
                .withBean(HikariDataSource.class, () -> {
                    var data = pool();
                    data.setConnectionInitSql("SET quizopia.monitoring_capture='false'");
                    return data;
                })
                .run(context -> assertEquals(
                        "SET quizopia.monitoring_capture='false'\n; SET quizopia.realtime_invalidation='true'",
                        context.getBean(HikariDataSource.class).getConnectionInitSql()));
    }

    @Test
    void reservedInitializationSqlAndRunningPoolsFailClosed() {
        runner().withBean(HikariDataSource.class, () -> {
                    var data = pool();
                    data.setConnectionInitSql("SET quizopia.realtime_invalidation='true'");
                    return data;
                })
                .run(context -> assertTrue(illegalState(context.getStartupFailure())));
    }

    @Test
    void productionProfileRejectsEitherEnabledFlag() {
        for (String flag : new String[] {"capture-enabled", "publisher-enabled"}) {
            runner().withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                    .withPropertyValues("quizopia.assessment.realtime-invalidation." + flag + "=true")
                    .run(context -> assertTrue(illegalState(context.getStartupFailure())));
        }
    }

    static boolean illegalState(Throwable failure) {
        while (failure != null) {
            if (failure instanceof IllegalStateException) return true;
            failure = failure.getCause();
        }
        return false;
    }

    @Test
    void publisherValidationRequiresPrivateBrokerConfiguration() {
        var properties = new RealtimeInvalidationProperties();
        properties.setPublisherEnabled(true);
        properties.setHost("broker.local");
        properties.setPort(5671);
        properties.setVirtualHost("realtime");
        properties.setUsername("publisher");
        properties.setPassword("secret");
        properties.validate(false);
        properties.setVirtualHost("/");
        assertThrows(IllegalStateException.class, () -> properties.validate(false));
        properties.setVirtualHost("realtime");
        properties.setAllowInsecureLoopback(true);
        assertThrows(IllegalStateException.class, () -> properties.validate(false));
        properties.setAllowInsecureLoopback(false);
        var bounds = new RealtimeInvalidationProperties();
        bounds.setClaimBatch(0);
        assertThrows(IllegalStateException.class, () -> bounds.validate(false));
        assertFalse(properties.toString().contains("secret"));
    }
}
