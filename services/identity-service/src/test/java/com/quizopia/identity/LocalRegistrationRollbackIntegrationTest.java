package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.quizopia.identity.application.registration.LocalRegistrationInput;
import com.quizopia.identity.application.registration.LocalRegistrationService;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.security.password.RawLocalPassword;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class LocalRegistrationRollbackIntegrationTest {
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
    private UserAccountRepository userAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LocalCredentialRepository localCredentialRepository;

    @BeforeEach
    void forceCredentialPersistenceFailure() {
        doThrow(new DataIntegrityViolationException("forced local credential failure"))
                .when(localCredentialRepository)
                .saveAndFlush(any(LocalCredentialEntity.class));
    }

    @Test
    void credentialFailureRollsBackThePreviouslyInsertedUser() {
        String username = "rollback-" + UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> registrationService.register(new LocalRegistrationInput(
                        username,
                        "rollback-" + UUID.randomUUID() + "@example.com",
                        RawLocalPassword.from("rollback-secret"))));

        assertEquals(
                0L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_account WHERE username = ?", Long.class, username));
        assertEquals(0L, userAccountRepository.count());
    }
}
