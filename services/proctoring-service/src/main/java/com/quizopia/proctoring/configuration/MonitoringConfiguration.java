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
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .addMixIn(com.quizopia.proctoring.domain.Monitoring.Event.class, EventWire.class);
    }

    abstract static class EventWire {
        @tools.jackson.databind.annotation.JsonDeserialize(using = IntegralLong.class)
        abstract Long sequence();

        @tools.jackson.databind.annotation.JsonDeserialize(using = IntegralInteger.class)
        abstract Integer questionNumber();

        @tools.jackson.databind.annotation.JsonDeserialize(using = IntegralInteger.class)
        abstract Integer schemaVersion();

        @tools.jackson.databind.annotation.JsonDeserialize(using = TimestampString.class)
        abstract java.time.Instant observedAt();
    }

    /** Token-specific Event constraints do not change unrelated Integer/Instant endpoint semantics. */
    public static class IntegralLong extends tools.jackson.databind.ValueDeserializer<Long> {
        @Override
        public Long deserialize(
                tools.jackson.core.JsonParser parser, tools.jackson.databind.DeserializationContext context) {
            if (!parser.hasToken(tools.jackson.core.JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class, "Event sequence requires an integer JSON token");
            return parser.getLongValue();
        }
    }

    public static class IntegralInteger extends tools.jackson.databind.ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(
                tools.jackson.core.JsonParser parser, tools.jackson.databind.DeserializationContext context) {
            if (!parser.hasToken(tools.jackson.core.JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Integer.class, "Event metadata requires an integer JSON token");
            return parser.getIntValue();
        }
    }

    public static class TimestampString extends tools.jackson.databind.ValueDeserializer<java.time.Instant> {
        private static final String RFC3339 = "[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])[Tt]"
                + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:(?:[0-5][0-9]|60)(?:\\.[0-9]{1,9})?"
                + "(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])";

        @Override
        public java.time.Instant deserialize(
                tools.jackson.core.JsonParser parser, tools.jackson.databind.DeserializationContext context) {
            if (!parser.hasToken(tools.jackson.core.JsonToken.VALUE_STRING))
                return context.reportInputMismatch(
                        java.time.Instant.class, "Event timestamp requires an ISO timestamp string");
            String wire = parser.getString();
            if (!wire.matches(RFC3339))
                return context.reportInputMismatch(
                        java.time.Instant.class, "Event timestamp requires an ISO timestamp string");
            try {
                return java.time.Instant.parse(wire);
            } catch (java.time.format.DateTimeParseException invalid) {
                return context.reportInputMismatch(
                        java.time.Instant.class, "Event timestamp requires an ISO timestamp string");
            }
        }
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
