package com.quizopia.classroom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = ClassroomServiceApplicationTests.SmokeConfiguration.class)
@ActiveProfiles("test")
class ClassroomServiceApplicationTests {
    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.Profile("test")
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.Import({
        com.quizopia.classroom.configuration.SecurityConfiguration.class,
        com.quizopia.classroom.security.QuizopiaJwtAuthenticationConverter.class,
        com.quizopia.classroom.security.ClassroomSecurityErrorHandler.class
    })
    static class SmokeConfiguration {}

    @Test
    void contextLoads() {}
}
