package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.application.googlelink.GoogleIdentityLinkResult;
import com.quizopia.identity.application.googlelink.GoogleIdentityLinkService;
import com.quizopia.identity.application.googlelink.GoogleIdentityLinkStatus;
import com.quizopia.identity.application.googlelink.VerifiedGoogleIdentity;
import com.quizopia.identity.persistence.entity.ExternalProviderIdentityEntity;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.entity.RefreshTokenEntity;
import com.quizopia.identity.persistence.entity.RefreshTokenFamilyEntity;
import com.quizopia.identity.persistence.entity.UserAccessRevocationEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.ExternalProviderIdentityRepository;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.RefreshTokenFamilyRepository;
import com.quizopia.identity.persistence.repository.RefreshTokenRepository;
import com.quizopia.identity.persistence.repository.UserAccessRevocationRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
class GoogleIdentityLinkIntegrationTest {
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
    private GoogleIdentityLinkService linkService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private LocalCredentialRepository localCredentialRepository;

    @Autowired
    private ExternalProviderIdentityRepository externalProviderIdentityRepository;

    @Autowired
    private RefreshTokenFamilyRepository refreshTokenFamilyRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserAccessRevocationRepository userAccessRevocationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void verifiedGoogleInputRejectsMissingIdentityFacts() {
        assertThrows(NullPointerException.class, () -> new VerifiedGoogleIdentity(null, "user@example.com"));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedGoogleIdentity(" ", "user@example.com"));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedGoogleIdentity("subject", " "));
    }

    @Test
    void verifiedLocalAccountLinksAndReturnsInternalUserId() {
        UserAccountEntity account = verifiedAccount("safe-link-" + UUID.randomUUID() + "@example.com");
        String subject = "subject-" + UUID.randomUUID();

        GoogleIdentityLinkResult result = resolve(subject, account.getEmail());

        assertEquals(GoogleIdentityLinkStatus.LINKED_EXISTING_USER, result.status());
        assertEquals(account.getId(), result.userId().orElseThrow());
        assertEquals(
                account.getId(),
                externalProviderIdentityRepository
                        .findByProviderAndProviderSubject("google", subject)
                        .orElseThrow()
                        .getUser()
                        .getId());
    }

    @Test
    void repeatedIdenticalLinkIsIdempotentWithoutDuplicateProviderRows() {
        UserAccountEntity account = verifiedAccount("idempotent-" + UUID.randomUUID() + "@example.com");
        String subject = "subject-" + UUID.randomUUID();

        GoogleIdentityLinkResult first = resolve(subject, account.getEmail());
        GoogleIdentityLinkResult second = resolve(subject, account.getEmail());

        assertEquals(GoogleIdentityLinkStatus.LINKED_EXISTING_USER, first.status());
        assertEquals(GoogleIdentityLinkStatus.ALREADY_LINKED, second.status());
        assertEquals(account.getId(), second.userId().orElseThrow());
        assertEquals(1L, providerCount(subject));
    }

    @Test
    void unverifiedLocalEmailDoesNotLink() {
        UserAccountEntity account = persistAccount("unverified-" + UUID.randomUUID() + "@example.com");
        String subject = "subject-" + UUID.randomUUID();

        GoogleIdentityLinkResult result = resolve(subject, account.getEmail());

        assertEquals(GoogleIdentityLinkStatus.NO_SAFE_MATCH, result.status());
        assertTrue(result.userId().isEmpty());
        assertEquals(0L, providerCount(subject));
    }

    @Test
    void noSafeMatchDoesNotCreateQuizopiaUserOrProviderIdentity() {
        long usersBefore = userAccountRepository.count();
        long providersBefore = externalProviderIdentityRepository.count();

        GoogleIdentityLinkResult result = resolve("subject-" + UUID.randomUUID(), "missing@example.com");

        assertEquals(GoogleIdentityLinkStatus.NO_SAFE_MATCH, result.status());
        assertEquals(usersBefore, userAccountRepository.count());
        assertEquals(providersBefore, externalProviderIdentityRepository.count());
    }

    @Test
    void existingSubjectOwnedByAnotherUserCannotBeStolen() {
        UserAccountEntity owner = verifiedAccount("owner-" + UUID.randomUUID() + "@example.com");
        UserAccountEntity candidate = verifiedAccount("candidate-" + UUID.randomUUID() + "@example.com");
        String subject = "subject-" + UUID.randomUUID();
        persistProvider(owner, subject);

        GoogleIdentityLinkResult result = resolve(subject, candidate.getEmail());

        assertEquals(GoogleIdentityLinkStatus.CONFLICT, result.status());
        assertEquals(owner.getId(), providerOwner(subject));
        assertEquals(0L, providerCountForUser(candidate.getId()));
    }

    @Test
    void candidateWithDifferentGoogleSubjectIsAConflict() {
        UserAccountEntity account = verifiedAccount("conflict-" + UUID.randomUUID() + "@example.com");
        persistProvider(account, "existing-subject-" + UUID.randomUUID());

        GoogleIdentityLinkResult result = resolve("new-subject-" + UUID.randomUUID(), account.getEmail());

        assertEquals(GoogleIdentityLinkStatus.CONFLICT, result.status());
        assertEquals(1L, providerCountForUser(account.getId()));
    }

    @Test
    void exactEmailEqualityDoesNotApplySpeculativeGmailCanonicalization() {
        UserAccountEntity account = verifiedAccount("person@example.com");

        GoogleIdentityLinkResult result = resolve("subject-" + UUID.randomUUID(), "person+alias@example.com");

        assertEquals(GoogleIdentityLinkStatus.NO_SAFE_MATCH, result.status());
        assertEquals(0L, providerCountForUser(account.getId()));
    }

    @Test
    void linkingDoesNotMutateRolesCredentialsOrAccountStatus() {
        UserAccountEntity account = verifiedAccount("side-effects-" + UUID.randomUUID() + "@example.com");
        account.setAccountStatus("PENDING");
        userAccountRepository.saveAndFlush(account);
        userRoleRepository.saveAndFlush(new UserRoleEntity(account, UserRole.STUDENT));
        userRoleRepository.saveAndFlush(new UserRoleEntity(account, UserRole.TEACHER));
        String passwordHash = "$argon2id$v=19$m=65536,t=3,p=1$test$encoded";
        localCredentialRepository.saveAndFlush(new LocalCredentialEntity(account, passwordHash));
        Set<UserRole> rolesBefore = rolesFor(account.getId());

        GoogleIdentityLinkResult result = resolve("subject-" + UUID.randomUUID(), account.getEmail());
        UserAccountEntity reloaded =
                userAccountRepository.findById(account.getId()).orElseThrow();

        assertEquals(GoogleIdentityLinkStatus.LINKED_EXISTING_USER, result.status());
        assertEquals(rolesBefore, rolesFor(account.getId()));
        assertEquals(
                passwordHash,
                localCredentialRepository
                        .findByUserId(account.getId())
                        .orElseThrow()
                        .getPasswordHash());
        assertEquals("PENDING", reloaded.getAccountStatus());
    }

    @Test
    void linkingDoesNotCreateRefreshOrRevocationSideEffects() {
        UserAccountEntity account = verifiedAccount("no-side-effects-" + UUID.randomUUID() + "@example.com");
        RefreshTokenFamilyEntity family = refreshTokenFamilyRepository.saveAndFlush(
                new RefreshTokenFamilyEntity(account, Instant.parse("2035-01-01T00:00:00Z")));
        refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, new byte[] {0x01, 0x02, 0x03}));
        userAccessRevocationRepository.saveAndFlush(
                new UserAccessRevocationEntity(account.getId(), Instant.parse("2026-01-01T00:00:00Z")));
        long familiesBefore = refreshTokenFamilyRepository.count();
        long tokensBefore = refreshTokenRepository.count();
        long revocationsBefore = userAccessRevocationRepository.count();

        GoogleIdentityLinkResult result = resolve("subject-" + UUID.randomUUID(), account.getEmail());

        assertEquals(GoogleIdentityLinkStatus.LINKED_EXISTING_USER, result.status());
        assertEquals(familiesBefore, refreshTokenFamilyRepository.count());
        assertEquals(tokensBefore, refreshTokenRepository.count());
        assertEquals(revocationsBefore, userAccessRevocationRepository.count());
    }

    @Test
    void changedGoogleEmailCannotMoveAnExistingProviderSubject() {
        UserAccountEntity original = verifiedAccount("original-" + UUID.randomUUID() + "@example.com");
        UserAccountEntity changedEmailCandidate = verifiedAccount("changed-" + UUID.randomUUID() + "@example.com");
        String subject = "subject-" + UUID.randomUUID();
        persistProvider(original, subject);

        GoogleIdentityLinkResult result = resolve(subject, changedEmailCandidate.getEmail());

        assertEquals(GoogleIdentityLinkStatus.CONFLICT, result.status());
        assertEquals(original.getId(), providerOwner(subject));
    }

    @Test
    void multipleExactEmailCandidatesAreAmbiguous() {
        String email = "duplicate-" + UUID.randomUUID() + "@example.com";
        String subject = "subject-" + UUID.randomUUID();
        verifiedAccount(email);
        verifiedAccount(email);

        GoogleIdentityLinkResult result = resolve(subject, email);

        assertEquals(GoogleIdentityLinkStatus.CONFLICT, result.status());
        assertEquals(0L, providerCount(subject));
    }

    @Test
    void concurrentFirstLinksCannotAssignOneSubjectToTwoUsers() throws Exception {
        UserAccountEntity first = verifiedAccount("race-first-" + UUID.randomUUID() + "@example.com");
        UserAccountEntity second = verifiedAccount("race-second-" + UUID.randomUUID() + "@example.com");
        String subject = "race-subject-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<GoogleIdentityLinkResult> firstResult =
                    executor.submit(() -> resolveAfter(ready, release, subject, first.getEmail()));
            Future<GoogleIdentityLinkResult> secondResult =
                    executor.submit(() -> resolveAfter(ready, release, subject, second.getEmail()));
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            release.countDown();

            Set<GoogleIdentityLinkStatus> statuses = Set.of(
                    firstResult.get(30, TimeUnit.SECONDS).status(),
                    secondResult.get(30, TimeUnit.SECONDS).status());
            assertEquals(
                    Set.of(GoogleIdentityLinkStatus.LINKED_EXISTING_USER, GoogleIdentityLinkStatus.CONFLICT), statuses);
            assertEquals(1L, providerCount(subject));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentSubjectsCannotAttachTwoGoogleIdentitiesToOneUser() throws Exception {
        UserAccountEntity account = verifiedAccount("same-user-race-" + UUID.randomUUID() + "@example.com");
        String firstSubject = "first-race-subject-" + UUID.randomUUID();
        String secondSubject = "second-race-subject-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<GoogleIdentityLinkResult> firstResult =
                    executor.submit(() -> resolveAfter(ready, release, firstSubject, account.getEmail()));
            Future<GoogleIdentityLinkResult> secondResult =
                    executor.submit(() -> resolveAfter(ready, release, secondSubject, account.getEmail()));
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            release.countDown();

            Set<GoogleIdentityLinkStatus> statuses = Set.of(
                    firstResult.get(30, TimeUnit.SECONDS).status(),
                    secondResult.get(30, TimeUnit.SECONDS).status());
            assertEquals(
                    Set.of(GoogleIdentityLinkStatus.LINKED_EXISTING_USER, GoogleIdentityLinkStatus.CONFLICT), statuses);
            assertEquals(1L, providerCountForUser(account.getId()));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private GoogleIdentityLinkResult resolve(String subject, String email) {
        return linkService.resolve(new VerifiedGoogleIdentity(subject, email));
    }

    private GoogleIdentityLinkResult resolveAfter(
            CountDownLatch ready, CountDownLatch release, String subject, String email) throws Exception {
        ready.countDown();
        if (!release.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent linking release");
        }
        return resolve(subject, email);
    }

    private UserAccountEntity verifiedAccount(String email) {
        UserAccountEntity account = persistAccount(email);
        account.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return userAccountRepository.saveAndFlush(account);
    }

    private UserAccountEntity persistAccount(String email) {
        return userAccountRepository.saveAndFlush(new UserAccountEntity(email, null));
    }

    private void persistProvider(UserAccountEntity account, String subject) {
        externalProviderIdentityRepository.saveAndFlush(new ExternalProviderIdentityEntity(account, "google", subject));
    }

    private long providerCount(String subject) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM external_provider_identity WHERE provider = 'google' AND provider_subject = ?",
                Long.class,
                subject);
    }

    private long providerCountForUser(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM external_provider_identity WHERE provider = 'google' AND user_id = ?",
                Long.class,
                userId);
    }

    private UUID providerOwner(String subject) {
        return externalProviderIdentityRepository
                .findByProviderAndProviderSubject("google", subject)
                .orElseThrow()
                .getUser()
                .getId();
    }

    private Set<UserRole> rolesFor(UUID userId) {
        return userRoleRepository.findAllByUser_Id(userId).stream()
                .map(UserRoleEntity::getRole)
                .collect(Collectors.toSet());
    }
}
