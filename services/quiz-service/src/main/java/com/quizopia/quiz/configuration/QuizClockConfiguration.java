package com.quizopia.quiz.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QuizClockConfiguration {
    @Bean
    Clock quizClock() {
        return Clock.systemUTC();
    }
}
