package com.quizopia.assessment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AssessmentServiceApplicationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.quizopia.assessment.application.AttemptPersistence attempts;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.quizopia.assessment.application.PublicationStore store;

    @Test
    void contextLoads() {}
}
