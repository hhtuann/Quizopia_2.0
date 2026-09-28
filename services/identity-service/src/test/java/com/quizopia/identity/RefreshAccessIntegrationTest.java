package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationInput;
import com.quizopia.identity.application.login.InitialLoginService;
import com.quizopia.identity.application.refresh.RefreshAccessService;
import com.quizopia.identity.application.refresh.RefreshAccessStatus;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.RefreshTokenRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.password.RawLocalPassword;
import com.quizopia.identity.security.refresh.RefreshCredentialGenerator;
import com.quizopia.identity.security.refresh.RefreshCredentialHasher;
import com.quizopia.identity.security.token.QuizopiaTokenClaims;
import com.quizopia.identity.security.token.UserAccessTokenIssuanceException;
import com.quizopia.identity.security.token.UserAccessTokenIssuer;
import com.quizopia.identity.support.TestRsaKeyMaterial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
class RefreshAccessIntegrationTest {
    private static final String ISSUER = "https://identity.refresh.test";
    private static final String KEY_ID = "wave-2a-refresh-test-key";
    private static final Duration USER_ACCESS_TOKEN_TTL = Duration.ofMinutes(5);
    private static final TestRsaKeyMaterial KEY_MATERIAL = TestRsaKeyMaterial.create("quizopia-refresh-");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private InitialLoginService loginService;

    @Autowired
    private RefreshAccessService refreshAccessService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private LocalCredentialRepository localCredentialRepository;

    @Autowired
    @Qualifier("serviceClientPasswordEncoder") private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshCredentialHasher refreshCredentialHasher;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean(name = "identityClock")
    private Clock clock;

    @MockitoSpyBean
    private UserAccessTokenIssuer accessTokenIssuer;

    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoSpyBean
    private RefreshCredentialGenerator refreshCredentialGenerator;

    private Instant loginTime;
    private Instant refreshTime;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("quizopia.identity.security.authorization-server.enabled", () -> true);
        registry.add("quizopia.identity.security.authorization-server.issuer", () -> ISSUER);
        registry.add("quizopia.identity.security.authorization-server.service-access-token-ttl", () -> "PT45S");
        registry.add(
                "quizopia.identity.security.authorization-server.user-access-token-ttl",
                USER_ACCESS_TOKEN_TTL::toString);
        registry.add("quizopia.identity.security.signing-key.kid", () -> KEY_ID);
        registry.add(
                "quizopia.identity.security.signing-key.private-key-path",
                () -> KEY_MATERIAL.privateKeyPath().toString());
        registry.add(
                "quizopia.identity.security.signing-key.public-key-path",
                () -> KEY_MATERIAL.publicKeyPath().toString());
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM refresh_token_family");
        jdbc.update("DELETE FROM local_credential");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM user_access_revocation");
        jdbc.update("DELETE FROM user_account");
        loginTime =
                jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Instant.class).truncatedTo(ChronoUnit.SECONDS);
        refreshTime = loginTime.plus(Duration.ofDays(2));
        when(clock.instant()).thenReturn(loginTime);
    }

    @AfterAll
    static void removeTestKeys() throws Exception {
        KEY_MATERIAL.close();
    }

    @Test
    void successfulRefreshRotatesHashOnlyCredentialAndPreservesFamilyExpiryAndJwtContract() throws Exception {
        UserAccountEntity user = persistUser();
        var initial = login(user);
        var initialSession = initial.session().orElseThrow();
        when(clock.instant()).thenReturn(refreshTime);

        var result = refreshAccessService.refresh(initialSession.refreshCredential());

        assertEquals(RefreshAccessStatus.REFRESHED, result.status());
        var refreshed = result.session().orElseThrow();
        assertEquals(initialSession.familyExpiresAt(), refreshed.familyExpiresAt());
        assertEquals(
                Duration.between(refreshTime, initialSession.familyExpiresAt()).getSeconds(),
                refreshed.cookieMaxAgeSeconds());
        assertFalse(initialSession
                .refreshCredential()
                .value()
                .equals(refreshed.refreshCredential().value()));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(2L, count("SELECT COUNT(*) FROM refresh_token"));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token WHERE consumed_at IS NOT NULL"));
        assertArrayEquals(
                refreshCredentialHasher.hash(refreshed.refreshCredential()),
                jdbc.queryForObject("SELECT token_hash FROM refresh_token WHERE consumed_at IS NULL", byte[].class));

        SignedJWT jwt = SignedJWT.parse(refreshed.accessToken().value());
        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
        assertEquals(KEY_ID, jwt.getHeader().getKeyID());
        assertEquals(ISSUER, jwt.getJWTClaimsSet().getIssuer());
        assertEquals(user.getId().toString(), jwt.getJWTClaimsSet().getSubject());
        assertEquals(
                QuizopiaTokenClaims.USER, jwt.getJWTClaimsSet().getStringClaim(QuizopiaTokenClaims.PRINCIPAL_TYPE));
        assertEquals(Set.of("STUDENT"), Set.copyOf(jwt.getJWTClaimsSet().getStringListClaim("roles")));
        assertEquals(refreshTime, jwt.getJWTClaimsSet().getIssueTime().toInstant());
        assertEquals(
                refreshTime.plus(USER_ACCESS_TOKEN_TTL),
                jwt.getJWTClaimsSet().getExpirationTime().toInstant());
    }

    @Test
    void jwtFailureRollsBackRotationAndLeavesPreviousCredentialUsable() {
        UserAccountEntity user = persistUser();
        var initial = login(user).session().orElseThrow();
        when(clock.instant()).thenReturn(refreshTime);
        doThrow(new UserAccessTokenIssuanceException()).when(accessTokenIssuer).issue(user.getId());

        assertThrows(
                UserAccessTokenIssuanceException.class,
                () -> refreshAccessService.refresh(initial.refreshCredential()));

        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM refresh_token", Instant.class));
        doCallRealMethod().when(accessTokenIssuer).issue(user.getId());
        assertEquals(
                RefreshAccessStatus.REFRESHED,
                refreshAccessService.refresh(initial.refreshCredential()).status());
    }

    @Test
    void replayCommitsFamilyRevocationAndInvalidatesReplacement() {
        UserAccountEntity user = persistUser();
        var initial = login(user).session().orElseThrow();
        when(clock.instant()).thenReturn(refreshTime);
        var replacement = refreshAccessService
                .refresh(initial.refreshCredential())
                .session()
                .orElseThrow()
                .refreshCredential();

        assertEquals(
                RefreshAccessStatus.REJECTED,
                refreshAccessService.refresh(initial.refreshCredential()).status());
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NOT NULL"));
        assertEquals(
                RefreshAccessStatus.REJECTED,
                refreshAccessService.refresh(replacement).status());
    }

    @Test
    void subsecondRemainingFamilyLifetimeRejectsWithoutConsumingOrReplacingCredential() {
        UserAccountEntity user = persistUser();
        var initial = login(user).session().orElseThrow();
        Instant almostExpired = initial.familyExpiresAt().minusMillis(500);
        when(clock.instant()).thenReturn(almostExpired);

        assertEquals(
                RefreshAccessStatus.REJECTED,
                refreshAccessService.refresh(initial.refreshCredential()).status());
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM refresh_token", Instant.class));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NOT NULL"));
    }

    @Test
    void exactlyOneSecondRemainingIsAcceptedWithPrevalidatedPositiveCookieLifetime() {
        UserAccountEntity user = persistUser();
        var initial = login(user).session().orElseThrow();
        Instant refreshAt = persistedSessionCreationCeiling().plusSeconds(1);
        Instant expiresAt = refreshAt.plusSeconds(1);
        updateFamilyExpiryAndAssertConstraint(expiresAt);
        when(clock.instant()).thenReturn(refreshAt);

        var result = refreshAccessService.refresh(initial.refreshCredential());

        assertEquals(RefreshAccessStatus.REFRESHED, result.status());
        assertEquals(expiresAt, result.session().orElseThrow().familyExpiresAt());
        assertEquals(1L, result.session().orElseThrow().cookieMaxAgeSeconds());
    }

    @Test
    void oneSecondAndEpsilonUsesSnapshotFloorWithoutExtendingFamilyExpiry() {
        UserAccountEntity user = persistUser();
        var initial = login(user).session().orElseThrow();
        Instant refreshAt = persistedSessionCreationCeiling().plusSeconds(1);
        Instant expiresAt = refreshAt.plusMillis(1001);
        updateFamilyExpiryAndAssertConstraint(expiresAt);
        when(clock.instant()).thenReturn(refreshAt);

        var result = refreshAccessService.refresh(initial.refreshCredential());

        assertEquals(RefreshAccessStatus.REFRESHED, result.status());
        assertEquals(expiresAt, result.session().orElseThrow().familyExpiresAt());
        assertEquals(1L, result.session().orElseThrow().cookieMaxAgeSeconds());
    }

    @Test
    void casZeroRollsBackReplacementThenSeparateRecoveryRevokesFamily() {
        UserAccountEntity user = persistUser();
        var initial = login(user).session().orElseThrow();
        when(clock.instant()).thenReturn(refreshTime);
        doReturn(0).when(refreshTokenRepository).consumeIfUnused(any(UUID.class), eq(refreshTime));

        assertEquals(
                RefreshAccessStatus.REJECTED,
                refreshAccessService.refresh(initial.refreshCredential()).status());

        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM refresh_token", Instant.class));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NOT NULL"));
        assertEquals(
                RefreshAccessStatus.REJECTED,
                refreshAccessService.refresh(initial.refreshCredential()).status());
    }

    @Test
    void pessimisticFamilyLockSerializesLegitimateConcurrentRotationsBeforeConditionalConsume() throws Exception {
        UserAccountEntity user = persistUser();
        var initial = login(user).session().orElseThrow();
        when(clock.instant()).thenReturn(refreshTime);
        CountDownLatch firstReachedCredentialGeneration = new CountDownLatch(1);
        CountDownLatch releaseFirstRotation = new CountDownLatch(1);
        doAnswer(invocation -> {
                    firstReachedCredentialGeneration.countDown();
                    if (!releaseFirstRotation.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release first rotation");
                    }
                    return invocation.callRealMethod();
                })
                .when(refreshCredentialGenerator)
                .generate();

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<com.quizopia.identity.application.refresh.RefreshAccessResult> first =
                    executor.submit(() -> refreshAccessService.refresh(initial.refreshCredential()));
            assertTrue(firstReachedCredentialGeneration.await(30, TimeUnit.SECONDS));
            Future<com.quizopia.identity.application.refresh.RefreshAccessResult> second =
                    executor.submit(() -> refreshAccessService.refresh(initial.refreshCredential()));

            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
            releaseFirstRotation.countDown();

            var firstResult = first.get(30, TimeUnit.SECONDS);
            var secondResult = second.get(30, TimeUnit.SECONDS);
            assertEquals(RefreshAccessStatus.REFRESHED, firstResult.status());
            assertEquals(RefreshAccessStatus.REJECTED, secondResult.status());
            assertEquals(2L, count("SELECT COUNT(*) FROM refresh_token"));
            assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NOT NULL"));
            assertEquals(
                    RefreshAccessStatus.REJECTED,
                    refreshAccessService
                            .refresh(firstResult.session().orElseThrow().refreshCredential())
                            .status());
        } finally {
            releaseFirstRotation.countDown();
            executor.shutdownNow();
        }
    }

    private com.quizopia.identity.application.login.InitialLoginResult login(UserAccountEntity user) {
        return loginService.login(new LocalAuthenticationInput(user.getUsername(), RawLocalPassword.from("secret")));
    }

    private UserAccountEntity persistUser() {
        UserAccountEntity user =
                new UserAccountEntity("refresh-" + UUID.randomUUID() + "@gmail.com", "refresh-" + UUID.randomUUID());
        user.setAccountStatus(AccountLifecycleStatus.ACTIVE);
        user.setEmailVerifiedAt(loginTime.minusSeconds(60));
        user = userAccountRepository.saveAndFlush(user);
        localCredentialRepository.saveAndFlush(new LocalCredentialEntity(user, passwordEncoder.encode("secret")));
        userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        return user;
    }

    private Instant persistedSessionCreationCeiling() {
        return jdbc.queryForObject(
                """
                SELECT GREATEST(family.created_at, token.created_at)
                FROM refresh_token_family family
                JOIN refresh_token token ON token.family_id = family.id
                WHERE token.consumed_at IS NULL
                """,
                Instant.class);
    }

    private void updateFamilyExpiryAndAssertConstraint(Instant expiresAt) {
        jdbc.update("UPDATE refresh_token_family SET expires_at = ?", java.sql.Timestamp.from(expiresAt));
        assertTrue(jdbc.queryForObject("SELECT expires_at > created_at FROM refresh_token_family", Boolean.class));
    }

    private long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }
}
