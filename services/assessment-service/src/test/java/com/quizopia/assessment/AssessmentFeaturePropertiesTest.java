package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.configuration.AssessmentFeatureProperties;
import com.quizopia.assessment.security.admission.PublicationRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AssessmentFeaturePropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AssessmentFeatureProperties.class)
    static class Binding {}

    @Test
    void defaultsAreOffAndExplicitFlagsDoNotAuthorizeFutureRoutes() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);
        runner.run(c -> {
            var f = c.getBean(AssessmentFeatureProperties.class);
            assertFalse(f.attemptsEnabled());
            assertFalse(f.resultsEnabled());
            assertFalse(f.closureEnabled());
        });
        runner.withPropertyValues(
                        "quizopia.assessment.features.attempts-enabled=true",
                        "quizopia.assessment.features.results-enabled=true",
                        "quizopia.assessment.features.closure-enabled=true")
                .run(c -> {
                    var f = c.getBean(AssessmentFeatureProperties.class);
                    assertTrue(f.attemptsEnabled());
                    assertTrue(f.resultsEnabled());
                    assertTrue(f.closureEnabled());
                    for (String path : new String[] {
                        "/api/assessments/attempts/00000000-0000-0000-0000-000000000001/submit",
                        "/api/assessments/publications/00000000-0000-0000-0000-000000000001/attempts/start",
                        "/api/assessments/publications/00000000-0000-0000-0000-000000000001/close",
                        "/internal/security/notice-recipients/00000000-0000-0000-0000-000000000001/eligibility"
                    }) assertNull(PublicationRoutes.requiredRole("POST", path));
                });
    }
}
