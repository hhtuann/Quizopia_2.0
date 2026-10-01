package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationInput;
import com.quizopia.identity.application.login.InitialLoginResult;
import com.quizopia.identity.application.login.InitialLoginService;
import com.quizopia.identity.application.login.InitialLoginSessionPolicy;
import com.quizopia.identity.application.login.InitialLoginStatus;
import com.quizopia.identity.application.refresh.RefreshSessionService;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
class InitialLoginIntegrationTest {
    private static final String ISSUER = "https://identity.login.test";
    private static final String KEY_ID = "wave-2a-login-test-key";
    private static final Duration USER_ACCESS_TOKEN_TTL = Duration.ofMinutes(5);
    private static final TestRsaKeyMaterial KEY_MATERIAL = TestRsaKeyMaterial.create("quizopia-login-");

    private Instant loginTime;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private InitialLoginService loginService;

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
    private RefreshSessionService refreshSessionService;

    @MockitoSpyBean
    private RefreshCredentialGenerator refreshCredentialGenerator;

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
        loginTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        when(clock.instant()).thenReturn(loginTime);
    }

    @AfterAll
    static void removeTestKeys() throws Exception {
        KEY_MATERIAL.close();
    }

    @Test
    void usernameLoginCreatesOneHashedSevenDaySessionAndRealUserJwt() throws Exception {
        String password = "username-login-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistUser("username", password, AccountLifecycleStatus.ACTIVE, true, true);

        InitialLoginResult result = login(user.getUsername(), password);

        assertEquals(InitialLoginStatus.AUTHENTICATED, result.status());
        var session = result.session().orElseThrow();
        assertEquals(loginTime.plus(InitialLoginSessionPolicy.FAMILY_LIFETIME), session.familyExpiresAt());
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", user.getId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertEquals(
                session.familyExpiresAt(),
                jdbc.queryForObject(
                        "SELECT expires_at FROM refresh_token_family WHERE user_id = ?", Instant.class, user.getId()));
        byte[] persistedHash = jdbc.queryForObject("SELECT token_hash FROM refresh_token", byte[].class);
        assertArrayEquals(refreshCredentialHasher.hash(session.refreshCredential()), persistedHash);
        assertFalse(java.util.HexFormat.of()
                .formatHex(persistedHash)
                .contains(session.refreshCredential().value()));

        SignedJWT jwt = SignedJWT.parse(session.accessToken().value());
        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
        assertEquals(KEY_ID, jwt.getHeader().getKeyID());
        assertEquals(ISSUER, jwt.getJWTClaimsSet().getIssuer());
        assertEquals(user.getId().toString(), jwt.getJWTClaimsSet().getSubject());
        assertEquals(
                QuizopiaTokenClaims.USER, jwt.getJWTClaimsSet().getStringClaim(QuizopiaTokenClaims.PRINCIPAL_TYPE));
        assertEquals(Set.of("STUDENT"), Set.copyOf(jwt.getJWTClaimsSet().getStringListClaim("roles")));
        assertEquals(loginTime, jwt.getJWTClaimsSet().getIssueTime().toInstant());
        assertEquals(
                loginTime.plus(USER_ACCESS_TOKEN_TTL),
                jwt.getJWTClaimsSet().getExpirationTime().toInstant());
    }

    @Test
    void verifiedEmailAndSameAccountDualMatchEachCreateOnlyOneSession() {
        String emailPassword = "email-login-secret-" + UUID.randomUUID();
        UserAccountEntity emailUser = persistUser("email", emailPassword, AccountLifecycleStatus.ACTIVE, true, true);
        assertEquals(
                InitialLoginStatus.AUTHENTICATED,
                login(emailUser.getEmail(), emailPassword).status());

        String dualIdentifier = "dual-" + UUID.randomUUID() + "@gmail.com";
        String dualPassword = "dual-login-secret-" + UUID.randomUUID();
        UserAccountEntity dualUser =
                persistUser(dualIdentifier, dualIdentifier, dualPassword, AccountLifecycleStatus.ACTIVE, true, true);
        assertEquals(
                InitialLoginStatus.AUTHENTICATED,
                login(dualIdentifier, dualPassword).status());

        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", emailUser.getId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", dualUser.getId()));
        assertEquals(2L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void everyCredentialOrEligibilityRejectionLeavesNoRefreshState() {
        String password = "generic-login-failure-" + UUID.randomUUID();
        UserAccountEntity eligible = persistUser("eligible", password, AccountLifecycleStatus.ACTIVE, true, true);
        UserAccountEntity unverified =
                persistUser("unverified", password, AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION, false, false);
        UserAccountEntity disabled = persistUser("disabled", password, "DISABLED", true, true);
        UserAccountEntity roleless = persistUser("roleless", password, AccountLifecycleStatus.ACTIVE, true, false);
        UserAccountEntity noCredential = persistUser("no-credential", null, AccountLifecycleStatus.ACTIVE, true, true);
        String ambiguous = "ambiguous-" + UUID.randomUUID() + "@gmail.com";
        persistUser(
                ambiguous,
                "username-owner-" + UUID.randomUUID() + "@gmail.com",
                password,
                AccountLifecycleStatus.ACTIVE,
                true,
                true);
        persistUser("email-owner-" + UUID.randomUUID(), ambiguous, password, AccountLifecycleStatus.ACTIVE, true, true);

        for (InitialLoginResult result : List.of(
                login("unknown-" + UUID.randomUUID(), password),
                login(eligible.getUsername(), "wrong-" + password),
                login(unverified.getUsername(), password),
                login(disabled.getUsername(), password),
                login(roleless.getUsername(), password),
                login(noCredential.getUsername(), password),
                login(ambiguous, password))) {
            assertEquals(InitialLoginResult.invalidCredentials(), result);
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void jwtIssuanceFailureRollsBackInitialRefreshFamilyAndCredential() {
        String password = "jwt-rollback-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistUser("jwt-rollback", password, AccountLifecycleStatus.ACTIVE, true, true);
        doThrow(new UserAccessTokenIssuanceException()).when(accessTokenIssuer).issue(user.getId());

        assertThrows(UserAccessTokenIssuanceException.class, () -> login(user.getUsername(), password));

        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void refreshPersistenceFailureNeverInvokesJwtIssuerOrReturnsSuccess() {
        String password = "session-failure-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistUser("session-failure", password, AccountLifecycleStatus.ACTIVE, true, true);
        doThrow(new IllegalStateException("sanitized persistence failure"))
                .when(refreshSessionService)
                .issueInitialIfEligible(eq(user.getId()), any(), any());

        assertThrows(IllegalStateException.class, () -> login(user.getUsername(), password));

        verify(accessTokenIssuer, never()).issue(user.getId());
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void disableCommittedAfterPasswordCheckIsObservedByAuthoritativeIssuanceReload() throws Exception {
        String password = "disable-first-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistUser("disable-first", password, AccountLifecycleStatus.ACTIVE, true, true);
        CountDownLatch authenticationPassed = new CountDownLatch(1);
        CountDownLatch allowIssuance = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
                    authenticationPassed.countDown();
                    assertTrue(allowIssuance.await(30, TimeUnit.SECONDS));
                    return invocation.callRealMethod();
                })
                .when(refreshSessionService)
                .issueInitialIfEligible(eq(user.getId()), any(), any());

        var executor = Executors.newSingleThreadExecutor();
        try {
            var login = executor.submit(() -> login(user.getUsername(), password));
            assertTrue(authenticationPassed.await(30, TimeUnit.SECONDS));
            assertEquals(
                    1, jdbc.update("UPDATE user_account SET account_status = 'DISABLED' WHERE id = ?", user.getId()));
            allowIssuance.countDown();

            assertEquals(
                    InitialLoginStatus.INVALID_CREDENTIALS,
                    login.get(30, TimeUnit.SECONDS).status());
            assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family"));
            assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token"));
        } finally {
            allowIssuance.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void loginHoldingAuthoritativeUserLockSerializesConcurrentDisable() throws Exception {
        String password = "login-first-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistUser("login-first", password, AccountLifecycleStatus.ACTIVE, true, true);
        CountDownLatch issuanceLockHeld = new CountDownLatch(1);
        CountDownLatch allowCredentialGeneration = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
                    issuanceLockHeld.countDown();
                    assertTrue(allowCredentialGeneration.await(30, TimeUnit.SECONDS));
                    return invocation.callRealMethod();
                })
                .when(refreshCredentialGenerator)
                .generate();

        var executor = Executors.newFixedThreadPool(2);
        try {
            var login = executor.submit(() -> login(user.getUsername(), password));
            assertTrue(issuanceLockHeld.await(30, TimeUnit.SECONDS));
            CountDownLatch disableStarted = new CountDownLatch(1);
            var disable = executor.submit(() -> {
                disableStarted.countDown();
                return jdbc.update("UPDATE user_account SET account_status = 'DISABLED' WHERE id = ?", user.getId());
            });
            assertTrue(disableStarted.await(30, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> disable.get(300, TimeUnit.MILLISECONDS));

            allowCredentialGeneration.countDown();
            InitialLoginResult authenticated = login.get(30, TimeUnit.SECONDS);
            assertEquals(InitialLoginStatus.AUTHENTICATED, authenticated.status());
            assertEquals(1, disable.get(30, TimeUnit.SECONDS));
            assertEquals(
                    "DISABLED",
                    jdbc.queryForObject(
                            "SELECT account_status FROM user_account WHERE id = ?", String.class, user.getId()));
        } finally {
            allowCredentialGeneration.countDown();
            executor.shutdownNow();
        }
    }

    private InitialLoginResult login(String identifier, String password) {
        return loginService.login(new LocalAuthenticationInput(identifier, RawLocalPassword.from(password)));
    }

    private UserAccountEntity persistUser(
            String prefix, String password, String status, boolean verified, boolean student) {
        return persistUser(
                prefix + "-" + UUID.randomUUID(),
                prefix + "-" + UUID.randomUUID() + "@gmail.com",
                password,
                status,
                verified,
                student);
    }

    private UserAccountEntity persistUser(
            String username, String email, String password, String status, boolean verified, boolean student) {
        UserAccountEntity user = new UserAccountEntity(email, username);
        user.setAccountStatus(status);
        user.setEmailVerifiedAt(verified ? loginTime.minusSeconds(60) : null);
        user = userAccountRepository.saveAndFlush(user);
        if (password != null) {
            localCredentialRepository.saveAndFlush(new LocalCredentialEntity(user, passwordEncoder.encode(password)));
        }
        if (student) {
            userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        }
        return user;
    }

    private long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }
}
