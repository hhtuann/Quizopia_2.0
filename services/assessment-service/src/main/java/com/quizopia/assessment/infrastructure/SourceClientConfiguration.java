package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.security.admission.AdmissionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SourceProperties.class)
public class SourceClientConfiguration {
    @Bean(destroyMethod = "close")
    PublicationSourceClient publicationSourceClient(SourceProperties sources, AdmissionProperties admission) {
        return new PublicationSourceClient(sources, admission);
    }
}
