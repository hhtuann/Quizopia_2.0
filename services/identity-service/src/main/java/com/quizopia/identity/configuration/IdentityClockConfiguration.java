package com.quizopia.identity.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdentityClockConfiguration {
    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }
}
