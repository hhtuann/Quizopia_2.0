package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.security.admission.AdmissionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SourceProperties.class, StudentProfileProperties.class})
public class SourceClientConfiguration {
    @Bean(destroyMethod = "close")
    ProctoringTargetClient proctoringTargetClient(
            com.quizopia.assessment.configuration.ProctoringAuthorityProperties capability,
            SourceProperties sources,
            AdmissionProperties admission) {
        return new ProctoringTargetClient(capability, sources, admission);
    }

    @Bean(destroyMethod = "close")
    PublicationSourceClient publicationSourceClient(SourceProperties sources, AdmissionProperties admission) {
        return new PublicationSourceClient(sources, admission);
    }

    @Bean(destroyMethod = "close")
    StudentProfileClient studentProfileClient(StudentProfileProperties profiles, AdmissionProperties admission) {
        return new StudentProfileClient(profiles, admission);
    }
}
