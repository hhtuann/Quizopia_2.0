package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.activation.TrustedEmailActivationInput;
import com.quizopia.identity.application.activation.TrustedEmailActivationResult;
import com.quizopia.identity.application.activation.TrustedEmailActivationService;
import com.quizopia.identity.application.activation.TrustedEmailActivationStatus;
import com.quizopia.identity.application.registration.LocalRegistrationInput;
import com.quizopia.identity.application.registration.LocalRegistrationService;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.password.RawLocalPassword;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
class TrustedEmailActivationIntegrationTest {
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
    private TrustedEmailActivationService activationService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private LocalCredentialRepository localCredentialRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void pendingAccountActivatesWithVerifiedTimestampAndStudentOnly() {
        UUID userId = register("activation-" + UUID.randomUUID());
        Instant verifiedAt = Instant.parse("2026-09-08T01:02:03Z");
        String passwordHash = credentialHash(userId);

        TrustedEmailActivationResult result = activate(userId, verifiedAt);

        assertEquals(TrustedEmailActivationStatus.ACTIVATED, result.status());
        assertEquals(verifiedAt, result.verifiedAt().orElseThrow());
        UserAccountEntity account = userAccountRepository.findById(userId).orElseThrow();
        assertEquals(AccountLifecycleStatus.ACTIVE, account.getAccountStatus());
        assertEquals(verifiedAt, account.getEmailVerifiedAt());
        assertEquals(Set.of(UserRole.STUDENT), roles(userId));
        assertEquals(passwordHash, credentialHash(userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM external_provider_identity WHERE user_id = ?", userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM user_access_revocation WHERE user_id = ?", userId));
    }

    @Test
    void activationAddsStudentWithoutRemovingExistingTeacherOrAdmin() {
        UUID userId = register("additive-" + UUID.randomUUID());
        UserAccountEntity account = userAccountRepository.findById(userId).orElseThrow();
        userRoleRepository.saveAndFlush(new UserRoleEntity(account, UserRole.TEACHER));
        userRoleRepository.saveAndFlush(new UserRoleEntity(account, UserRole.ADMIN));

        TrustedEmailActivationResult result = activate(userId, Instant.parse("2026-09-08T02:03:04Z"));

        assertEquals(TrustedEmailActivationStatus.ACTIVATED, result.status());
        assertEquals(Set.of(UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN), roles(userId));
    }

    @Test
    void repeatedActivationIsIdempotentAndPreservesOriginalVerificationTimestamp() {
        UUID userId = register("idempotent-activation-" + UUID.randomUUID());
        Instant firstVerifiedAt = Instant.parse("2026-09-08T03:04:05Z");
        Instant repeatedVerifiedAt = Instant.parse("2026-09-08T04:05:06Z");
        String passwordHash = credentialHash(userId);

        TrustedEmailActivationResult first = activate(userId, firstVerifiedAt);
        TrustedEmailActivationResult repeated = activate(userId, repeatedVerifiedAt);

        assertEquals(TrustedEmailActivationStatus.ACTIVATED, first.status());
        assertEquals(TrustedEmailActivationStatus.ALREADY_ACTIVE, repeated.status());
        assertEquals(firstVerifiedAt, repeated.verifiedAt().orElseThrow());
        assertEquals(
                firstVerifiedAt,
                userAccountRepository.findById(userId).orElseThrow().getEmailVerifiedAt());
        assertEquals(1L, count("SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role = 'STUDENT'", userId));
        assertEquals(passwordHash, credentialHash(userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM user_access_revocation WHERE user_id = ?", userId));
    }

    @Test
    void activationRejectsUnknownAndInconsistentAccountsSafely() {
        UUID unknownUserId = UUID.randomUUID();
        TrustedEmailActivationResult notFound = activate(unknownUserId, Instant.now());
        assertEquals(TrustedEmailActivationStatus.NOT_FOUND, notFound.status());

        UUID inconsistentPendingId = register("inconsistent-pending-" + UUID.randomUUID());
        UserAccountEntity inconsistentPending =
                userAccountRepository.findById(inconsistentPendingId).orElseThrow();
        Instant existingVerification = Instant.parse("2026-09-08T05:06:07Z");
        inconsistentPending.setEmailVerifiedAt(existingVerification);
        userAccountRepository.saveAndFlush(inconsistentPending);

        TrustedEmailActivationResult pendingConflict =
                activate(inconsistentPendingId, Instant.parse("2026-09-08T06:07:08Z"));
        assertEquals(TrustedEmailActivationStatus.CONFLICT, pendingConflict.status());
        UserAccountEntity reloadedPending =
                userAccountRepository.findById(inconsistentPendingId).orElseThrow();
        assertEquals(AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION, reloadedPending.getAccountStatus());
        assertEquals(existingVerification, reloadedPending.getEmailVerifiedAt());

        UUID inconsistentActiveId = register("inconsistent-active-" + UUID.randomUUID());
        UserAccountEntity inconsistentActive =
                userAccountRepository.findById(inconsistentActiveId).orElseThrow();
        inconsistentActive.setAccountStatus(AccountLifecycleStatus.ACTIVE);
        userAccountRepository.saveAndFlush(inconsistentActive);

        TrustedEmailActivationResult activeConflict =
                activate(inconsistentActiveId, Instant.parse("2026-09-08T07:08:09Z"));
        assertEquals(TrustedEmailActivationStatus.CONFLICT, activeConflict.status());
        assertTrue(userRoleRepository.findAllByUser_Id(inconsistentActiveId).isEmpty());
    }

    @Test
    void concurrentActivationsConvergeToOneTimestampAndStudentRole() throws Exception {
        UUID userId = register("concurrent-activation-" + UUID.randomUUID());
        Instant firstTimestamp = Instant.parse("2026-09-08T08:09:10Z");
        Instant secondTimestamp = Instant.parse("2026-09-08T08:09:11Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TrustedEmailActivationResult> first =
                    executor.submit(() -> activateAfter(ready, release, userId, firstTimestamp));
            Future<TrustedEmailActivationResult> second =
                    executor.submit(() -> activateAfter(ready, release, userId, secondTimestamp));
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            release.countDown();

            Set<TrustedEmailActivationStatus> statuses = Set.of(
                    first.get(30, TimeUnit.SECONDS).status(),
                    second.get(30, TimeUnit.SECONDS).status());
            assertEquals(
                    Set.of(TrustedEmailActivationStatus.ACTIVATED, TrustedEmailActivationStatus.ALREADY_ACTIVE),
                    statuses);
            UserAccountEntity account = userAccountRepository.findById(userId).orElseThrow();
            assertEquals(AccountLifecycleStatus.ACTIVE, account.getAccountStatus());
            assertTrue(Set.of(firstTimestamp, secondTimestamp).contains(account.getEmailVerifiedAt()));
            assertEquals(1L, count("SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role = 'STUDENT'", userId));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private UUID register(String username) {
        return registrationService
                .register(new LocalRegistrationInput(
                        username,
                        username + "@example.com",
                        RawLocalPassword.from("activation-secret-" + UUID.randomUUID())))
                .userId()
                .orElseThrow();
    }

    private TrustedEmailActivationResult activate(UUID userId, Instant verifiedAt) {
        return activationService.activate(new TrustedEmailActivationInput(userId, verifiedAt));
    }

    private TrustedEmailActivationResult activateAfter(
            CountDownLatch ready, CountDownLatch release, UUID userId, Instant verifiedAt) throws Exception {
        ready.countDown();
        if (!release.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent activation");
        }
        return activate(userId, verifiedAt);
    }

    private Set<UserRole> roles(UUID userId) {
        return userRoleRepository.findAllByUser_Id(userId).stream()
                .map(UserRoleEntity::getRole)
                .collect(java.util.stream.Collectors.toSet());
    }

    private String credentialHash(UUID userId) {
        return localCredentialRepository.findByUserId(userId).orElseThrow().getPasswordHash();
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
