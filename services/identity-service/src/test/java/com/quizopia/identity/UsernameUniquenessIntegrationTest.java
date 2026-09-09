package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
class UsernameUniquenessIntegrationTest {
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
    private UserAccountRepository userAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void flywayAppliesV1ThroughV6AndHibernateValidationStarts() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);

        assertEquals(List.of("1", "2", "3", "4", "5", "6"), versions);
    }

    @Test
    void exactDuplicateUsernameConflictsButCaseVariantsRemainDistinct() {
        String duplicateUsername = "alice-" + UUID.randomUUID();
        userAccountRepository.saveAndFlush(
                new UserAccountEntity("first-" + UUID.randomUUID() + "@example.com", duplicateUsername));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> userAccountRepository.saveAndFlush(
                        new UserAccountEntity("second-" + UUID.randomUUID() + "@example.com", duplicateUsername)));

        String caseVariant = duplicateUsername.toUpperCase(java.util.Locale.ROOT);
        userAccountRepository.saveAndFlush(
                new UserAccountEntity("case-" + UUID.randomUUID() + "@example.com", caseVariant));

        assertEquals(1L, usernameCount(duplicateUsername));
        assertEquals(1L, usernameCount(caseVariant));
    }

    @Test
    void multipleNullUsernamesRemainAllowed() {
        String firstEmail = "null-username-first-" + UUID.randomUUID() + "@example.com";
        String secondEmail = "null-username-second-" + UUID.randomUUID() + "@example.com";

        userAccountRepository.saveAndFlush(new UserAccountEntity(firstEmail, null));
        userAccountRepository.saveAndFlush(new UserAccountEntity(secondEmail, null));

        assertEquals(
                2L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_account WHERE email IN (?, ?)",
                        Long.class,
                        firstEmail,
                        secondEmail));
    }

    @Test
    void duplicateExactEmailsRemainAllowedByUsernameOnlyMigration() {
        String email = "same-email-" + UUID.randomUUID() + "@example.com";
        userAccountRepository.saveAndFlush(new UserAccountEntity(email, "email-first-" + UUID.randomUUID()));
        userAccountRepository.saveAndFlush(new UserAccountEntity(email, "email-second-" + UUID.randomUUID()));

        assertEquals(
                2L,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_account WHERE email = ?", Long.class, email));
    }

    @Test
    void concurrentExactDuplicateUsernameAllowsExactlyOneCommittedInsert() throws Exception {
        String username = "concurrent-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> attemptInsert(ready, release, username));
            Future<Boolean> second = executor.submit(() -> attemptInsert(ready, release, username));
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            release.countDown();

            Set<Boolean> outcomes = Set.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(Set.of(Boolean.TRUE, Boolean.FALSE), outcomes);
            assertEquals(1L, usernameCount(username));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private boolean attemptInsert(CountDownLatch ready, CountDownLatch release, String username) throws Exception {
        ready.countDown();
        if (!release.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent username inserts");
        }

        try {
            Timestamp now = Timestamp.from(Instant.now());
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> jdbcTemplate.update(
                            "INSERT INTO user_account "
                                    + "(id, email, username, account_status, email_verified_at, created_at, updated_at) "
                                    + "VALUES (?, ?, ?, NULL, NULL, ?, ?)",
                            UUID.randomUUID(),
                            "concurrent-email-" + UUID.randomUUID() + "@example.com",
                            username,
                            now,
                            now));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    private long usernameCount(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE username = ?", Long.class, username);
    }
}
