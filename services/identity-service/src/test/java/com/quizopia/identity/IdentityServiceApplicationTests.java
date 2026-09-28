package com.quizopia.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        properties = {
            "quizopia.identity.email-outbox.encryption.active-key-version=test-v1",
            "quizopia.identity.email-outbox.encryption.keys.test-v1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@ActiveProfiles("test")
class IdentityServiceApplicationTests {
    @Test
    void contextLoads() {}
}
