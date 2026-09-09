package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.registration.LocalRegistrationInput;
import com.quizopia.identity.application.registration.LocalRegistrationResult;
import com.quizopia.identity.application.registration.LocalRegistrationService;
import com.quizopia.identity.application.registration.LocalRegistrationStatus;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.security.password.RawLocalPassword;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
class LocalAccountRegistrationIntegrationTest {
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
    private LocalCredentialRepository localCredentialRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("serviceClientPasswordEncoder") private PasswordEncoder passwordEncoder;

    @Test
    void registrationCreatesCompletePendingAccountWithEncodedCredentialAndNoSideEffects() {
        String username = "registration-" + UUID.randomUUID();
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        String rawPassword = "Raw-registration-secret-" + UUID.randomUUID();

        LocalRegistrationResult result = register(username, email, rawPassword);

        assertEquals(LocalRegistrationStatus.CREATED_PENDING_VERIFICATION, result.status());
        UUID userId = result.userId().orElseThrow();
        UserAccountEntity account = userAccountRepository.findById(userId).orElseThrow();
        assertEquals(username, account.getUsername());
        assertEquals(email, account.getEmail());
        assertEquals(AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION, account.getAccountStatus());
        assertNotNull(account.getId());
        assertEquals(0L, count("SELECT COUNT(*) FROM user_role WHERE user_id = ?", userId));
        assertTrue(account.getEmailVerifiedAt() == null);

        String encodedPassword =
                localCredentialRepository.findByUserId(userId).orElseThrow().getPasswordHash();
        assertNotEquals(rawPassword, encodedPassword);
        assertFalse(encodedPassword.contains(rawPassword));
        assertFalse(encodedPassword.startsWith("{noop}"));
        assertTrue(passwordEncoder.matches(rawPassword, encodedPassword));
        assertEquals(1L, count("SELECT COUNT(*) FROM local_credential WHERE user_id = ?", userId));

        assertEquals(0L, count("SELECT COUNT(*) FROM external_provider_identity WHERE user_id = ?", userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", userId));
        assertEquals(
                0L,
                count(
                        "SELECT COUNT(*) FROM refresh_token token "
                                + "JOIN refresh_token_family family ON family.id = token.family_id "
                                + "WHERE family.user_id = ?",
                        userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM user_access_revocation WHERE user_id = ?", userId));
    }

    @Test
    void exactDuplicateUsernameReturnsConflictWithoutCreatingAnotherAccount() {
        String username = "duplicate-registration-" + UUID.randomUUID();
        register(username, "first-" + UUID.randomUUID() + "@example.com", "first-secret");

        LocalRegistrationResult result =
                register(username, "second-" + UUID.randomUUID() + "@example.com", "second-secret");

        assertEquals(LocalRegistrationStatus.USERNAME_CONFLICT, result.status());
        assertTrue(result.userId().isEmpty());
        assertEquals(1L, count("SELECT COUNT(*) FROM user_account WHERE username = ?", username));
        assertEquals(
                1L,
                count(
                        "SELECT COUNT(*) FROM local_credential credential "
                                + "JOIN user_account account ON account.id = credential.user_id "
                                + "WHERE account.username = ?",
                        username));
    }

    @Test
    void concurrentExactDuplicateRegistrationsCreateOneCompleteAccount() throws Exception {
        String username = "concurrent-registration-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<LocalRegistrationResult> first = executor.submit(
                    () -> registerAfter(ready, release, username, "first-" + UUID.randomUUID() + "@example.com"));
            Future<LocalRegistrationResult> second = executor.submit(
                    () -> registerAfter(ready, release, username, "second-" + UUID.randomUUID() + "@example.com"));
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            release.countDown();

            Set<LocalRegistrationStatus> statuses = Set.of(
                    first.get(30, TimeUnit.SECONDS).status(),
                    second.get(30, TimeUnit.SECONDS).status());
            assertEquals(
                    Set.of(
                            LocalRegistrationStatus.CREATED_PENDING_VERIFICATION,
                            LocalRegistrationStatus.USERNAME_CONFLICT),
                    statuses);
            assertEquals(1L, count("SELECT COUNT(*) FROM user_account WHERE username = ?", username));
            assertEquals(
                    1L,
                    count(
                            "SELECT COUNT(*) FROM local_credential credential "
                                    + "JOIN user_account account ON account.id = credential.user_id "
                                    + "WHERE account.username = ?",
                            username));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void caseVariantsAndDuplicateExactEmailRemainUnderExistingDatabaseSemantics() {
        String email = "duplicate-email-" + UUID.randomUUID() + "@example.com";

        LocalRegistrationResult upper = register("CaseUser-" + UUID.randomUUID(), email, "upper-secret");
        LocalRegistrationResult lower = register("caseuser-" + UUID.randomUUID(), email, "lower-secret");

        assertEquals(LocalRegistrationStatus.CREATED_PENDING_VERIFICATION, upper.status());
        assertEquals(LocalRegistrationStatus.CREATED_PENDING_VERIFICATION, lower.status());
        assertEquals(2L, count("SELECT COUNT(*) FROM user_account WHERE email = ?", email));
    }

    private LocalRegistrationResult register(String username, String email, String rawPassword) {
        return registrationService.register(
                new LocalRegistrationInput(username, email, RawLocalPassword.from(rawPassword)));
    }

    private LocalRegistrationResult registerAfter(
            CountDownLatch ready, CountDownLatch release, String username, String email) throws Exception {
        ready.countDown();
        if (!release.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent registrations");
        }
        return register(username, email, "Concurrent-registration-secret-" + UUID.randomUUID());
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
