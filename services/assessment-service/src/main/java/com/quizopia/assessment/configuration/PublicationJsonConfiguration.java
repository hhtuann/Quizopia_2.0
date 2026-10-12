package com.quizopia.assessment.configuration;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import tools.jackson.core.StreamReadFeature;

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
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
}
