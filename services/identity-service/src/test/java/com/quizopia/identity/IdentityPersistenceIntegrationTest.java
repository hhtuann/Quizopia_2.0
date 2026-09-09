package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.application.refresh.RefreshCredentialIssuance;
import com.quizopia.identity.application.refresh.RefreshRotationResult;
import com.quizopia.identity.application.refresh.RefreshRotationStatus;
import com.quizopia.identity.application.refresh.RefreshSessionService;
import com.quizopia.identity.application.serviceclient.ServiceClientAuthenticationMethod;
import com.quizopia.identity.application.serviceclient.ServiceClientDescriptor;
import com.quizopia.identity.application.serviceclient.ServiceClientGrantType;
import com.quizopia.identity.application.serviceclient.ServiceClientRegistration;
import com.quizopia.identity.application.serviceclient.ServiceClientRegistrationService;
import com.quizopia.identity.persistence.entity.ExternalProviderIdentityEntity;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.entity.RefreshTokenEntity;
import com.quizopia.identity.persistence.entity.RefreshTokenFamilyEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.ExternalProviderIdentityRepository;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.OAuth2ServiceClientRepository;
import com.quizopia.identity.persistence.repository.RefreshTokenFamilyRepository;
import com.quizopia.identity.persistence.repository.RefreshTokenRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.client.RawServiceClientSecret;
import com.quizopia.identity.security.client.ServiceClientSecretHasher;
import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.refresh.RefreshCredentialGenerator;
import com.quizopia.identity.security.refresh.RefreshCredentialHasher;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class IdentityPersistenceIntegrationTest {
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
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RefreshSessionService refreshSessionService;

    @Autowired
    private RefreshCredentialGenerator refreshCredentialGenerator;

    @Autowired
    private RefreshCredentialHasher refreshCredentialHasher;

    @Autowired
    private OAuth2ServiceClientRepository oauth2ServiceClientRepository;

    @Autowired
    private ServiceClientRegistrationService serviceClientRegistrationService;

    @Autowired
    private ServiceClientSecretHasher serviceClientSecretHasher;

    @Test
    void flywayMigratesEmptyDatabaseAndHibernateValidatesSchema() {
        assertNotNull(dataSource);
        assertNotNull(flyway.info().current());
        assertEquals("6", flyway.info().current().getVersion().getVersion());

        Integer accountTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'user_account'",
                Integer.class);
        assertEquals(1, accountTableCount);

        Integer familyTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'refresh_token_family'",
                Integer.class);
        assertEquals(1, familyTableCount);

        Integer tokenTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'refresh_token'",
                Integer.class);
        assertEquals(1, tokenTableCount);

        Integer serviceClientTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'oauth2_service_client'",
                Integer.class);
        assertEquals(1, serviceClientTableCount);

        Integer scopeTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'oauth2_service_client_scope'",
                Integer.class);
        assertEquals(1, scopeTableCount);
    }

    @Test
    void accountPersistsReloadsAndStoresVerificationStateWithoutWorkflow() {
        UserAccountEntity account = userAccountRepository.saveAndFlush(
                new UserAccountEntity("persistence-" + UUID.randomUUID() + "@example.com", null));
        account.setEmailVerifiedAt(Instant.parse("2026-01-02T03:04:05Z"));
        account.setAccountStatus(null);
        userAccountRepository.saveAndFlush(account);

        UserAccountEntity reloaded =
                userAccountRepository.findById(account.getId()).orElseThrow();
        assertEquals(account.getEmail(), reloaded.getEmail());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), reloaded.getEmailVerifiedAt());
        assertEquals(null, reloaded.getAccountStatus());
    }

    @Test
    void additiveRolesIncludingStudentAndTeacherCanCoexist() {
        UserAccountEntity account = persistAccount();
        userRoleRepository.saveAndFlush(new UserRoleEntity(account, UserRole.STUDENT));
        userRoleRepository.saveAndFlush(new UserRoleEntity(account, UserRole.TEACHER));

        Set<UserRole> roles = userRoleRepository.findAllByUser_Id(account.getId()).stream()
                .map(UserRoleEntity::getRole)
                .collect(Collectors.toSet());
        assertEquals(Set.of(UserRole.STUDENT, UserRole.TEACHER), roles);
    }

    @Test
    void duplicateUserRoleIsRejectedByDatabase() {
        UserAccountEntity account = persistAccount();
        userRoleRepository.saveAndFlush(new UserRoleEntity(account, UserRole.STUDENT));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> userRoleRepository.saveAndFlush(new UserRoleEntity(account, UserRole.STUDENT)));
    }

    @Test
    void unsupportedRoleValueIsRejectedByDatabase() {
        UserAccountEntity account = persistAccount();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO user_role (id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(),
                        account.getId(),
                        "OWNER",
                        OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void oneLocalCredentialRecordPerUserIsEnforcedAndOnlyHashIsMapped() {
        UserAccountEntity account = persistAccount();
        String encodedPassword = "$argon2id$v=19$m=65536,t=3,p=1$test$encoded";
        localCredentialRepository.saveAndFlush(new LocalCredentialEntity(account, encodedPassword));

        LocalCredentialEntity reloaded =
                localCredentialRepository.findByUserId(account.getId()).orElseThrow();
        assertEquals(encodedPassword, reloaded.getPasswordHash());
        assertTrue(List.of(LocalCredentialEntity.class.getDeclaredFields()).stream()
                .map(Field::getName)
                .anyMatch("passwordHash"::equals));
        assertFalse(List.of(LocalCredentialEntity.class.getDeclaredFields()).stream()
                .map(Field::getName)
                .anyMatch("password"::equals));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> localCredentialRepository.saveAndFlush(
                        new LocalCredentialEntity(account, encodedPassword + "-replacement")));
    }

    @Test
    void providerSubjectMapsToOnlyOneInternalUser() {
        UserAccountEntity firstAccount = persistAccount();
        UserAccountEntity secondAccount = persistAccount();
        externalProviderIdentityRepository.saveAndFlush(
                new ExternalProviderIdentityEntity(firstAccount, "google", "stable-subject"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> externalProviderIdentityRepository.saveAndFlush(
                        new ExternalProviderIdentityEntity(secondAccount, "google", "stable-subject")));
    }

    @Test
    void foreignKeysRejectNonexistentIdentityUsers() {
        UUID missingUserId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO user_role (id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(),
                        missingUserId,
                        "STUDENT",
                        OffsetDateTime.now(ZoneOffset.UTC)));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO local_credential (user_id, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?)",
                        missingUserId,
                        "encoded-only",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC)));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO external_provider_identity "
                                + "(id, user_id, provider, provider_subject, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        missingUserId,
                        "google",
                        "missing-user-subject",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void refreshFamilyPersistsExplicitExpiryAndReloadsForTheOwningUser() {
        UserAccountEntity account = persistAccount();
        Instant expiresAt = Instant.parse("2035-06-07T08:09:10Z");
        RefreshTokenFamilyEntity family =
                refreshTokenFamilyRepository.saveAndFlush(new RefreshTokenFamilyEntity(account, expiresAt));

        RefreshTokenFamilyEntity reloaded =
                refreshTokenFamilyRepository.findById(family.getId()).orElseThrow();
        assertEquals(account.getId(), reloaded.getUser().getId());
        assertEquals(expiresAt, reloaded.getExpiresAt());

        String expiresAtDefault = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name = 'refresh_token_family' "
                        + "AND column_name = 'expires_at'",
                String.class);
        assertNull(expiresAtDefault);
    }

    @Test
    void refreshFamilyRevocationTimestampPersists() {
        RefreshTokenFamilyEntity family = persistFamily();
        Instant revokedAt = Instant.parse("2026-02-03T04:05:06Z");

        family.setRevokedAt(revokedAt);
        refreshTokenFamilyRepository.saveAndFlush(family);

        RefreshTokenFamilyEntity reloaded =
                refreshTokenFamilyRepository.findById(family.getId()).orElseThrow();
        assertEquals(revokedAt, reloaded.getRevokedAt());
    }

    @Test
    void refreshTokenPersistsHashOnlyAndFindsItsFamily() {
        RefreshTokenFamilyEntity family = persistFamily();
        byte[] tokenHash = new byte[] {0x01, 0x23, (byte) 0xFE, (byte) 0xDC};

        RefreshTokenEntity token = refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, tokenHash));
        RefreshTokenEntity foundByHash =
                refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        RefreshTokenEntity reloaded =
                refreshTokenRepository.findByTokenHashWithFamily(tokenHash).orElseThrow();

        assertEquals(token.getId(), reloaded.getId());
        assertEquals(token.getId(), foundByHash.getId());
        assertArrayEquals(tokenHash, reloaded.getTokenHash());
        assertEquals(family.getId(), reloaded.getFamily().getId());
        assertTrue(List.of(RefreshTokenEntity.class.getDeclaredFields()).stream()
                .map(Field::getName)
                .noneMatch(name -> Set.of("token", "rawToken", "plaintextToken", "refreshToken")
                        .contains(name)));
    }

    @Test
    void duplicateRefreshTokenHashesAreRejectedByDatabase() {
        RefreshTokenFamilyEntity family = persistFamily();
        byte[] tokenHash = new byte[] {0x10, 0x20, 0x30};
        refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, tokenHash));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, tokenHash)));
    }

    @Test
    void refreshTokenCannotReferenceNonexistentFamily() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO refresh_token (id, family_id, token_hash, created_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new byte[] {0x01, 0x02},
                        OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void refreshFamilyCannotReferenceNonexistentIdentityUser() {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = OffsetDateTime.parse("2035-06-07T08:09:10Z");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO refresh_token_family "
                                + "(id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        createdAt,
                        expiresAt));
    }

    @Test
    void refreshTokenLineagePersistsAndCrossFamilyReplacementIsRejected() {
        RefreshTokenFamilyEntity family = persistFamily();
        RefreshTokenEntity first =
                refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, new byte[] {0x01, 0x02, 0x03}));
        RefreshTokenEntity replacement =
                refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, new byte[] {0x04, 0x05, 0x06}));

        Instant consumedAt = Instant.parse("2026-03-04T05:06:07Z");
        first.setConsumedAt(consumedAt);
        first.setReplacedByTokenId(replacement.getId());
        refreshTokenRepository.saveAndFlush(first);

        RefreshTokenEntity reloaded =
                refreshTokenRepository.findById(first.getId()).orElseThrow();
        assertEquals(consumedAt, reloaded.getConsumedAt());
        assertEquals(replacement.getId(), reloaded.getReplacedByTokenId());

        RefreshTokenFamilyEntity otherFamily = persistFamily();
        RefreshTokenEntity otherFamilyToken =
                refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(otherFamily, new byte[] {0x07, 0x08, 0x09}));
        RefreshTokenEntity invalidLineageToken =
                refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, new byte[] {0x0A, 0x0B, 0x0C}));
        invalidLineageToken.setConsumedAt(consumedAt);
        invalidLineageToken.setReplacedByTokenId(otherFamilyToken.getId());

        assertThrows(
                DataIntegrityViolationException.class, () -> refreshTokenRepository.saveAndFlush(invalidLineageToken));
    }

    @Test
    void applicationIssuancePersistsOnlyTheCredentialHash() {
        UserAccountEntity account = persistAccount();
        Instant familyExpiresAt = Instant.parse("2035-06-07T08:09:10Z");

        RefreshCredentialIssuance issuance = refreshSessionService.issueInitial(account.getId(), familyExpiresAt);

        RefreshTokenFamilyEntity persistedFamily =
                refreshTokenFamilyRepository.findById(issuance.familyId()).orElseThrow();
        assertEquals(account.getId(), persistedFamily.getUser().getId());
        assertEquals(familyExpiresAt, persistedFamily.getExpiresAt());
        byte[] persistedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM refresh_token WHERE id = ?", byte[].class, issuance.tokenId());
        assertArrayEquals(refreshCredentialHasher.hash(issuance.credential()), persistedHash);
        assertFalse(Arrays.equals(issuance.credential().value().getBytes(StandardCharsets.UTF_8), persistedHash));
        String persistedHashHex = jdbcTemplate.queryForObject(
                "SELECT encode(token_hash, 'hex') FROM refresh_token WHERE id = ?", String.class, issuance.tokenId());
        assertFalse(persistedHashHex.contains(issuance.credential().value()));
        assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM refresh_token WHERE family_id = ?", Long.class, issuance.familyId()));
    }

    @Test
    void applicationRotationConsumesOldCredentialPersistsLineageAndDetectsReuse() {
        UserAccountEntity account = persistAccount();
        Instant familyExpiresAt = Instant.parse("2035-06-07T08:09:10Z");
        Instant now = Instant.parse("2026-06-07T08:09:10Z");
        RefreshCredentialIssuance issuance = refreshSessionService.issueInitial(account.getId(), familyExpiresAt);

        RefreshRotationResult rotation = refreshSessionService.rotate(issuance.credential(), now);

        assertEquals(RefreshRotationStatus.SUCCESS, rotation.status());
        RawRefreshCredential replacementCredential =
                rotation.replacementCredential().orElseThrow();
        assertNotEquals(issuance.credential().value(), replacementCredential.value());

        RefreshTokenEntity consumed =
                refreshTokenRepository.findById(issuance.tokenId()).orElseThrow();
        assertEquals(now, consumed.getConsumedAt());
        assertNotNull(consumed.getReplacedByTokenId());
        RefreshTokenEntity replacement = refreshTokenRepository
                .findByTokenHashWithFamily(refreshCredentialHasher.hash(replacementCredential))
                .orElseThrow();
        assertEquals(consumed.getReplacedByTokenId(), replacement.getId());
        assertEquals(issuance.familyId(), replacement.getFamily().getId());
        assertEquals(
                2L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM refresh_token WHERE family_id = ?", Long.class, issuance.familyId()));

        RefreshRotationResult reuse = refreshSessionService.rotate(issuance.credential(), now.plusSeconds(1));
        assertEquals(RefreshRotationStatus.REUSE_DETECTED, reuse.status());
        assertTrue(reuse.replacementCredential().isEmpty());
        assertEquals(
                2L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM refresh_token WHERE family_id = ?", Long.class, issuance.familyId()));
    }

    @Test
    void concurrentRevocationIsObservedBeforeRotationValidation() throws Exception {
        UserAccountEntity account = persistAccount();
        Instant familyExpiresAt = Instant.parse("2035-06-07T08:09:10Z");
        Instant now = Instant.parse("2026-06-07T08:09:10Z");
        Instant revokedAt = Instant.parse("2026-06-07T08:09:09Z");
        RefreshCredentialIssuance issuance = refreshSessionService.issueInitial(account.getId(), familyExpiresAt);
        assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM refresh_token WHERE family_id = ?", Long.class, issuance.familyId()));
        CountDownLatch familyLockHeld = new CountDownLatch(1);
        CountDownLatch allowRevocationCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> revocation = executor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    lockRow(
                            connection,
                            "SELECT id FROM refresh_token_family WHERE id = ? FOR UPDATE",
                            issuance.familyId());
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE refresh_token_family SET revoked_at = ? WHERE id = ?")) {
                        statement.setObject(1, revokedAt.atOffset(ZoneOffset.UTC));
                        statement.setObject(2, issuance.familyId());
                        statement.executeUpdate();
                    }
                    familyLockHeld.countDown();
                    awaitLatch(allowRevocationCommit, "Timed out waiting to release family revocation lock");
                    connection.commit();
                    return null;
                }
            });
            assertTrue(familyLockHeld.await(30, TimeUnit.SECONDS));

            Future<RefreshRotationResult> rotation =
                    executor.submit(() -> refreshSessionService.rotate(issuance.credential(), now));
            awaitLockWaiters("refresh_token_family", 1);
            allowRevocationCommit.countDown();

            assertEquals(
                    RefreshRotationStatus.REVOKED_FAMILY,
                    rotation.get(30, TimeUnit.SECONDS).status());
            revocation.get(30, TimeUnit.SECONDS);

            RefreshTokenEntity original =
                    refreshTokenRepository.findById(issuance.tokenId()).orElseThrow();
            assertNull(original.getConsumedAt());
            assertNull(original.getReplacedByTokenId());
            RefreshTokenFamilyEntity persistedFamily =
                    refreshTokenFamilyRepository.findById(issuance.familyId()).orElseThrow();
            assertEquals(revokedAt, persistedFamily.getRevokedAt());
            assertEquals(
                    1L,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM refresh_token WHERE family_id = ?", Long.class, issuance.familyId()));
        } finally {
            allowRevocationCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void applicationRotationRejectsUnknownExpiredAndRevokedFamilies() {
        UserAccountEntity account = persistAccount();
        Instant familyExpiresAt = Instant.parse("2035-06-07T08:09:10Z");

        RefreshRotationResult unknown = refreshSessionService.rotate(
                refreshCredentialGenerator.generate(), Instant.parse("2026-06-07T08:09:10Z"));
        assertEquals(RefreshRotationStatus.UNKNOWN_CREDENTIAL, unknown.status());
        assertTrue(unknown.replacementCredential().isEmpty());

        RefreshCredentialIssuance expiredIssuance =
                refreshSessionService.issueInitial(account.getId(), familyExpiresAt);
        RefreshRotationResult expired =
                refreshSessionService.rotate(expiredIssuance.credential(), Instant.parse("2035-06-07T08:09:10Z"));
        assertEquals(RefreshRotationStatus.EXPIRED_FAMILY, expired.status());
        assertTrue(expired.replacementCredential().isEmpty());

        RefreshCredentialIssuance revokedIssuance =
                refreshSessionService.issueInitial(account.getId(), familyExpiresAt);
        RefreshTokenFamilyEntity revokedFamily = refreshTokenFamilyRepository
                .findById(revokedIssuance.familyId())
                .orElseThrow();
        revokedFamily.setRevokedAt(Instant.parse("2026-06-07T08:09:09Z"));
        refreshTokenFamilyRepository.saveAndFlush(revokedFamily);

        RefreshRotationResult revoked =
                refreshSessionService.rotate(revokedIssuance.credential(), Instant.parse("2026-06-07T08:09:10Z"));
        assertEquals(RefreshRotationStatus.REVOKED_FAMILY, revoked.status());
        assertTrue(revoked.replacementCredential().isEmpty());
    }

    @Test
    void concurrentApplicationRotationsAllowOneSuccessAndRollBackLosingReplacement() throws Exception {
        UserAccountEntity account = persistAccount();
        Instant familyExpiresAt = Instant.parse("2035-06-07T08:09:10Z");
        Instant now = Instant.parse("2026-06-07T08:09:10Z");
        RefreshCredentialIssuance issuance = refreshSessionService.issueInitial(account.getId(), familyExpiresAt);
        CountDownLatch tokenLockHeld = new CountDownLatch(1);
        CountDownLatch allowTokenLockRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);

        try {
            Future<?> tokenLock = executor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    lockRow(connection, "SELECT id FROM refresh_token WHERE id = ? FOR UPDATE", issuance.tokenId());
                    tokenLockHeld.countDown();
                    awaitLatch(allowTokenLockRelease, "Timed out waiting to release token CAS lock");
                    connection.commit();
                    return null;
                }
            });
            assertTrue(tokenLockHeld.await(30, TimeUnit.SECONDS));

            Future<RefreshRotationResult> first =
                    executor.submit(() -> refreshSessionService.rotate(issuance.credential(), now));
            Future<RefreshRotationResult> second =
                    executor.submit(() -> refreshSessionService.rotate(issuance.credential(), now));
            awaitLockWaiters("refresh_token", 2);
            allowTokenLockRelease.countDown();

            RefreshRotationResult firstResult = first.get(30, TimeUnit.SECONDS);
            RefreshRotationResult secondResult = second.get(30, TimeUnit.SECONDS);
            assertEquals(
                    Set.of(RefreshRotationStatus.SUCCESS, RefreshRotationStatus.REUSE_DETECTED),
                    Set.of(firstResult.status(), secondResult.status()));

            RefreshRotationResult successfulResult =
                    firstResult.status() == RefreshRotationStatus.SUCCESS ? firstResult : secondResult;
            RefreshRotationResult nonSuccessfulResult =
                    firstResult.status() == RefreshRotationStatus.SUCCESS ? secondResult : firstResult;
            assertTrue(nonSuccessfulResult.replacementCredential().isEmpty());

            RefreshTokenEntity consumed =
                    refreshTokenRepository.findById(issuance.tokenId()).orElseThrow();
            assertEquals(now, consumed.getConsumedAt());
            assertNotNull(consumed.getReplacedByTokenId());
            RefreshTokenEntity replacement = refreshTokenRepository
                    .findByTokenHashWithFamily(refreshCredentialHasher.hash(
                            successfulResult.replacementCredential().orElseThrow()))
                    .orElseThrow();
            assertEquals(consumed.getReplacedByTokenId(), replacement.getId());
            assertEquals(issuance.familyId(), replacement.getFamily().getId());
            assertEquals(
                    2L,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM refresh_token WHERE family_id = ?", Long.class, issuance.familyId()));
        } finally {
            allowTokenLockRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void compareAndSetConsumptionAllowsOnlyOneTransition() {
        RefreshTokenEntity token = refreshTokenRepository.saveAndFlush(
                new RefreshTokenEntity(persistFamily(), new byte[] {0x21, 0x22, 0x23}));
        Instant firstConsumption = Instant.parse("2026-04-05T06:07:08Z");
        Instant secondConsumption = Instant.parse("2026-04-05T06:07:09Z");

        assertEquals(1, refreshTokenRepository.consumeIfUnused(token.getId(), firstConsumption));
        assertEquals(0, refreshTokenRepository.consumeIfUnused(token.getId(), secondConsumption));

        RefreshTokenEntity reloaded =
                refreshTokenRepository.findById(token.getId()).orElseThrow();
        assertEquals(firstConsumption, reloaded.getConsumedAt());
    }

    @Test
    void concurrentCompareAndSetConsumptionAllowsOnlyOneSuccess() throws Exception {
        RefreshTokenEntity token = refreshTokenRepository.saveAndFlush(
                new RefreshTokenEntity(persistFamily(), new byte[] {0x31, 0x32, 0x33}));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        try {
            Future<Integer> first = executor.submit(() -> consumeAfter(
                    ready, release, transactionTemplate, token.getId(), Instant.parse("2026-05-06T07:08:09Z")));
            Future<Integer> second = executor.submit(() -> consumeAfter(
                    ready, release, transactionTemplate, token.getId(), Instant.parse("2026-05-06T07:08:10Z")));

            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for both CAS workers");
            }
            release.countDown();

            assertEquals(Set.of(0, 1), Set.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)));
            assertNotNull(
                    refreshTokenRepository.findById(token.getId()).orElseThrow().getConsumedAt());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void serviceClientPersistsAndReloadsWithClientCredentialsPolicy() {
        String clientId = "test-service-client-" + UUID.randomUUID();
        ServiceClientRegistration registration =
                serviceClientRegistrationService.register(clientId, List.of("test.read", "test.write"));

        ServiceClientDescriptor reloaded =
                serviceClientRegistrationService.findByClientId(clientId).orElseThrow();
        assertEquals(registration.serviceClientId(), reloaded.serviceClientId());
        assertEquals(clientId, reloaded.clientId());
        assertTrue(reloaded.enabled());
        assertEquals(Set.of("test.read", "test.write"), reloaded.scopes());
        assertEquals(ServiceClientAuthenticationMethod.CLIENT_SECRET_BASIC, reloaded.authenticationMethod());
        assertEquals(ServiceClientGrantType.CLIENT_CREDENTIALS, reloaded.grantType());
        assertNotNull(reloaded.createdAt());
        assertNotNull(reloaded.updatedAt());
    }

    @Test
    void serviceClientSecretIsEncodedAndMatchesOnlyTheOriginalSecret() {
        String clientId = "test-secret-client-" + UUID.randomUUID();
        ServiceClientRegistration registration = serviceClientRegistrationService.register(clientId, List.of());

        String persistedHash = jdbcTemplate.queryForObject(
                "SELECT client_secret_hash FROM oauth2_service_client WHERE client_id = ?", String.class, clientId);
        assertNotEquals(registration.clientSecret().value(), persistedHash);
        assertTrue(persistedHash.startsWith("{bcrypt}"));
        assertTrue(serviceClientSecretHasher.matches(registration.clientSecret(), persistedHash));
        assertFalse(serviceClientSecretHasher.matches(new RawServiceClientSecret("wrong-secret"), persistedHash));
        assertFalse(persistedHash.contains(registration.clientSecret().value()));
        assertEquals(
                0L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE table_name = 'oauth2_service_client' "
                                + "AND column_name IN ('client_secret', 'secret')",
                        Long.class));
    }

    @Test
    void duplicateServiceClientIdIsRejectedByDatabase() {
        String clientId = "test-unique-client-" + UUID.randomUUID();
        serviceClientRegistrationService.register(clientId, List.of());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> serviceClientRegistrationService.register(clientId, List.of("test.read")));
    }

    @Test
    void serviceClientEnabledStatePersistsAndCanBeChanged() {
        String clientId = "test-state-client-" + UUID.randomUUID();
        ServiceClientRegistration registration = serviceClientRegistrationService.register(clientId, List.of());
        ServiceClientDescriptor initiallyEnabled =
                serviceClientRegistrationService.findByClientId(clientId).orElseThrow();

        ServiceClientDescriptor disabled = serviceClientRegistrationService.setEnabled(clientId, false);
        assertFalse(disabled.enabled());
        assertEquals(initiallyEnabled.createdAt(), disabled.createdAt());
        assertNotNull(disabled.updatedAt());
        assertFalse(serviceClientRegistrationService
                .findByClientId(clientId)
                .orElseThrow()
                .enabled());

        assertTrue(serviceClientRegistrationService.setEnabled(clientId, true).enabled());
        assertEquals(
                registration.serviceClientId(),
                serviceClientRegistrationService
                        .findByClientId(clientId)
                        .orElseThrow()
                        .serviceClientId());
    }

    @Test
    void serviceClientScopesAreUniquePerClientAndRemainClientLocal() {
        String firstClientId = "test-scope-client-a-" + UUID.randomUUID();
        String secondClientId = "test-scope-client-b-" + UUID.randomUUID();
        ServiceClientRegistration first =
                serviceClientRegistrationService.register(firstClientId, List.of("test.read", "test.write"));
        serviceClientRegistrationService.register(secondClientId, List.of("test.read"));

        assertEquals(
                Set.of("test.read", "test.write"),
                serviceClientRegistrationService
                        .findByClientId(firstClientId)
                        .orElseThrow()
                        .scopes());
        assertEquals(
                Set.of("test.read"),
                serviceClientRegistrationService
                        .findByClientId(secondClientId)
                        .orElseThrow()
                        .scopes());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO oauth2_service_client_scope (service_client_id, scope) VALUES (?, ?)",
                        first.serviceClientId(),
                        "test.read"));
    }

    @Test
    void deletingServiceClientDeletesItsScopesAndDoesNotReferenceUsers() {
        ServiceClientRegistration registration = serviceClientRegistrationService.register(
                "test-delete-client-" + UUID.randomUUID(), List.of("test.read"));

        assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM oauth2_service_client_scope WHERE service_client_id = ?",
                        Long.class,
                        registration.serviceClientId()));
        assertEquals(
                0L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) "
                                + "FROM information_schema.table_constraints tc "
                                + "JOIN information_schema.constraint_column_usage ccu "
                                + "ON tc.constraint_name = ccu.constraint_name "
                                + "AND tc.table_schema = ccu.table_schema "
                                + "WHERE tc.table_name IN ('oauth2_service_client', 'oauth2_service_client_scope') "
                                + "AND tc.constraint_type = 'FOREIGN KEY' "
                                + "AND ccu.table_name = 'user_account'",
                        Long.class));

        oauth2ServiceClientRepository.deleteById(registration.serviceClientId());

        assertEquals(
                0L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM oauth2_service_client_scope WHERE service_client_id = ?",
                        Long.class,
                        registration.serviceClientId()));
        assertTrue(serviceClientRegistrationService
                .findByClientId(registration.clientId())
                .isEmpty());
    }

    @Test
    void foreignKeysRemainIdentityLocal() {
        List<String> referencedTables = jdbcTemplate.queryForList(
                "SELECT DISTINCT ccu.table_name "
                        + "FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.constraint_column_usage ccu "
                        + "ON tc.constraint_name = ccu.constraint_name "
                        + "AND tc.table_schema = ccu.table_schema "
                        + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                        + "AND tc.table_schema = 'public'",
                String.class);

        assertEquals(
                Set.of("user_account", "refresh_token_family", "refresh_token", "oauth2_service_client"),
                referencedTables.stream().collect(Collectors.toSet()));
    }

    private UserAccountEntity persistAccount() {
        return userAccountRepository.saveAndFlush(
                new UserAccountEntity("persistence-" + UUID.randomUUID() + "@example.com", null));
    }

    private RefreshTokenFamilyEntity persistFamily() {
        return refreshTokenFamilyRepository.saveAndFlush(
                new RefreshTokenFamilyEntity(persistAccount(), Instant.parse("2035-06-07T08:09:10Z")));
    }

    private int consumeAfter(
            CountDownLatch ready,
            CountDownLatch release,
            TransactionTemplate transactionTemplate,
            UUID tokenId,
            Instant consumedAt) {
        return transactionTemplate.execute(status -> {
            ready.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release CAS workers");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("CAS worker interrupted", exception);
            }
            return refreshTokenRepository.consumeIfUnused(tokenId, consumedAt);
        });
    }

    private void awaitLatch(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(timeoutMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency worker interrupted", exception);
        }
    }

    private void awaitLockWaiters(String tableName, int expectedWaiters) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity "
                            + "WHERE datname = current_database() "
                            + "AND state = 'active' "
                            + "AND wait_event_type = 'Lock' "
                            + "AND query ILIKE ?",
                    Integer.class,
                    "%" + tableName + "%");
            if (waiters != null && waiters >= expectedWaiters) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for database lock waiters", exception);
            }
        }
        throw new IllegalStateException(
                "Timed out waiting for " + expectedWaiters + " PostgreSQL lock waiter(s) on " + tableName);
    }

    private void lockRow(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Expected row was not found for lock coordination");
                }
            }
        }
    }
}
