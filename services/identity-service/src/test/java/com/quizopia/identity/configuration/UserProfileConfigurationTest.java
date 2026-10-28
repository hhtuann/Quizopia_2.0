package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class UserProfileConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UserProfileProperties.class)
    static class PropertiesOnly {}

    @Test
    void defaultsAreDisabledSecureExactAndBounded() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .run(context -> {
                    var value = context.getBean(UserProfileProperties.class);
                    assertFalse(value.enabled());
                    assertTrue(value.requireSecureTransport());
                    assertEquals("assessment-service", value.assessmentClientId());
                    assertEquals(8, value.maxConcurrent());
                    assertEquals(60, value.requestsPerMinute());
                });
        for (String client : new String[] {"*", "assessment-*", "assessment-service ", "a,b", null})
            assertThrows(IllegalArgumentException.class, () -> new UserProfileProperties(true, true, client, 8, 60));
        for (int bound : new int[] {0, 65})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new UserProfileProperties(true, true, "assessment-service", bound, 60));
        for (int rate : new int[] {0, 6001})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new UserProfileProperties(true, true, "assessment-service", 8, rate));
    }
}
