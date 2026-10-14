package com.quizopia.proctoring.configuration;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.infrastructure.AssessmentMonitoringClient;
import com.quizopia.proctoring.security.admission.AdmissionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfiguration {
    @Bean
    org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer strictMonitoringJson() {
        return builder -> builder.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.proctoring.monitoring.enabled", havingValue = "true")
    MonitoringAuthority monitoringAuthority(MonitoringProperties p, AdmissionProperties a, Environment env) {
        p.validate(env.matchesProfiles("prod"), a.isAllowInsecureLoopback());
        if (!a.isEnabled()) throw new IllegalStateException("Monitoring requires fresh Identity admission");
        return new AssessmentMonitoringClient(p, a);
    }

    @Bean
    @ConditionalOnProperty(name = "quizopia.proctoring.monitoring.enabled", havingValue = "true")
    MonitoringService monitoringService(MonitoringStore store, MonitoringAuthority authority) {
        return new MonitoringService(store, authority);
    }
}
