package com.quizopia.assessment.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.quizopia.assessment.domain.PublicationConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.util.AccessPattern;

@org.springframework.boot.context.properties.EnableConfigurationProperties(AssessmentFeatureProperties.class)
@Configuration(proxyBeanMethods = false)
public class PublicationJsonConfiguration {
    @Bean
    java.time.Clock publicationClock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    JsonMapperBuilderCustomizer strictPublicationJson() {
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .addMixIn(PublicationConfiguration.class, ConfigurationMixin.class);
    }

    abstract static class ConfigurationMixin {
        @JsonProperty(required = true)
        abstract int durationMinutes();

        @JsonDeserialize(using = MaxAttemptsDeserializer.class)
        abstract Integer maxAttempts();
    }

    /** Missing legacy configuration defaults to one; an explicit wire null remains invalid. */
    public static class MaxAttemptsDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "maxAttempts must be an integer");
            }
            return parser.getIntValue();
        }

        @Override
        public Integer getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(Integer.class, "maxAttempts must not be null");
        }

        @Override
        public Integer getAbsentValue(DeserializationContext context) {
            return 1;
        }

        @Override
        public AccessPattern getNullAccessPattern() {
            return AccessPattern.DYNAMIC;
        }
    }
}
