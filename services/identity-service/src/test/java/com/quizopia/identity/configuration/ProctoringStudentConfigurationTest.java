package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.identity.security.token.QuizopiaJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

class ProctoringStudentConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProctoringStudentProperties.class)
    static class PropertiesOnly {}

    @Test
    void defaultsAreDisabledSecureExactAndBounded() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .run(context -> {
                    var value = context.getBean(ProctoringStudentProperties.class);
                    assertFalse(value.enabled());
                    assertTrue(value.requireSecureTransport());
                    assertEquals("assessment-service", value.assessmentClientId());
                    assertEquals(8, value.maxConcurrent());
                });
        for (String client : new String[] {"*", "assessment-*", "assessment-service ", "a,b"})
            assertThrows(IllegalArgumentException.class, () -> new ProctoringStudentProperties(true, true, client, 8));
        for (int bound : new int[] {0, 65})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProctoringStudentProperties(true, true, "assessment-service", bound));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledProductionCapabilityCannotDisableHttps() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        var failure = assertThrows(IllegalStateException.class, () -> new UserAdmissionConfiguration()
                .userAdmissionChain(
                        mock(HttpSecurity.class),
                        new UserAdmissionProperties(),
                        new NoticeRecipientProperties(false, true, "assessment-service", 8),
                        new ProctoringStudentProperties(true, false, "assessment-service", 8),
                        new UserProfileProperties(false, true, "assessment-service", 8, 60),
                        environment,
                        mock(ObjectProvider.class),
                        mock(QuizopiaJwtAuthenticationConverter.class)));
        assertEquals("Private proctoring eligibility HTTPS is required in production", failure.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledProductionProfileBatchCannotDisableHttps() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        var failure = assertThrows(IllegalStateException.class, () -> new UserAdmissionConfiguration()
                .userAdmissionChain(
                        mock(HttpSecurity.class),
                        new UserAdmissionProperties(),
                        new NoticeRecipientProperties(false, true, "assessment-service", 8),
                        new ProctoringStudentProperties(false, true, "assessment-service", 8),
                        new UserProfileProperties(true, false, "assessment-service", 8, 60),
                        environment,
                        mock(ObjectProvider.class),
                        mock(QuizopiaJwtAuthenticationConverter.class)));
        assertEquals("Private user-profile HTTPS is required in production", failure.getMessage());
    }
}
