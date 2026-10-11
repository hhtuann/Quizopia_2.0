package com.quizopia.assessment.security.admission;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdmissionProperties.class)
public class AdmissionClientConfiguration {
    @Bean(destroyMethod = "close")
    IdentityAdmissionClient identityAdmissionClient(AdmissionProperties properties) {
        return new IdentityAdmissionClient(properties);
    }
}
