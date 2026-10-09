package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.quizopia.identity.application.registration.LocalRegistrationInput;
import com.quizopia.identity.application.registration.LocalRegistrationService;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.quizopia.identity.security.password.RawLocalPassword;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
class LocalRegistrationOutboxRollbackIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private LocalRegistrationService registrationService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OutboxPayloadCipher payloadCipher;

    @Test
    void outboxEncryptionFailureRollsBackAccountCredentialChallengeAndIssuance() {
        String username = "rollback-outbox-" + UUID.randomUUID();
        String email = "rollback-outbox-" + UUID.randomUUID() + "@gmail.com";
        when(payloadCipher.encrypt(any(), any())).thenThrow(new IllegalStateException("forced encryption failure"));

        assertThrows(
                IllegalStateException.class,
                () -> registrationService.register(
                        new LocalRegistrationInput(username, email, RawLocalPassword.from("rollback-secret"))));

        assertEquals(0L, count("SELECT COUNT(*) FROM user_account WHERE username = ?", username));
        assertEquals(
                0L,
                count(
                        "SELECT COUNT(*) FROM local_credential credential "
                                + "JOIN user_account account ON account.id = credential.user_id "
                                + "WHERE account.username = ?",
                        username));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", email));
        assertEquals(
                0L, count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", email));
        assertEquals(
                0L,
                count(
                        "SELECT COUNT(*) FROM email_verification_challenge challenge "
                                + "JOIN user_account account ON account.id = challenge.user_id "
                                + "WHERE account.username = ?",
                        username));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
