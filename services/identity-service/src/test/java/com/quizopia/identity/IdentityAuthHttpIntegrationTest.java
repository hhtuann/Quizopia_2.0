package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.identity.api.auth.AuthController;
import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.emailverification.EmailVerificationTimingProtector;
import com.quizopia.identity.application.logout.CurrentSessionLogoutService;
import com.quizopia.identity.application.registration.LocalRegistrationResult;
import com.quizopia.identity.application.registration.LocalRegistrationService;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.emailverification.EmailVerificationOtpGenerator;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.refresh.RefreshCredentialHasher;
import com.quizopia.identity.security.token.QuizopiaTokenClaims;
import com.quizopia.identity.security.token.UserAccessTokenIssuanceException;
import com.quizopia.identity.security.token.UserAccessTokenIssuer;
import com.quizopia.identity.support.TestRsaKeyMaterial;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "debug=false",
            "logging.level.org.springframework.web=INFO",
            "logging.level.org.springframework.jdbc=INFO",
            "logging.level.org.hibernate.SQL=INFO"
        })
@AutoConfigureTestRestTemplate
@ActiveProfiles("persistence-test")
@Import(IdentityAuthHttpIntegrationTest.TestResourceServerConfiguration.class)
class IdentityAuthHttpIntegrationTest {
    private static final String ISSUER = "https://identity.http-login.test";
    private static final String KEY_ID = "wave-2a-http-login-key";
    private static final String TRUSTED_ORIGIN = "http://localhost:3000";
    private static final Duration USER_ACCESS_TOKEN_TTL = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final RawEmailVerificationOtp OTP = RawEmailVerificationOtp.from("012345");
    private static final TestRsaKeyMaterial KEY_MATERIAL = TestRsaKeyMaterial.create("quizopia-http-login-");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("quizopia.identity.security.browser.trusted-origins", () -> TRUSTED_ORIGIN);
        registry.add("quizopia.identity.security.authorization-server.enabled", () -> true);
        registry.add("quizopia.identity.security.authorization-server.issuer", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
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

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RefreshCredentialHasher refreshCredentialHasher;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private LocalCredentialRepository localCredentialRepository;

    @Autowired
    @Qualifier("serviceClientPasswordEncoder") private PasswordEncoder passwordEncoder;

    @MockitoBean
    private EmailVerificationOtpGenerator otpGenerator;

    @MockitoBean(name = "identityClock")
    private Clock identityClock;

    @MockitoSpyBean
    private LocalRegistrationService registrationService;

    @MockitoSpyBean
    private UserAccessTokenIssuer accessTokenIssuer;

    @MockitoSpyBean
    private CurrentSessionLogoutService logoutService;

    @MockitoSpyBean
    private EmailVerificationTimingProtector verificationTimingProtector;

    @AfterAll
    static void removeTestKeys() throws Exception {
        KEY_MATERIAL.close();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM email_verification_email_outbox");
        jdbc.update("DELETE FROM email_verification_challenge");
        jdbc.update("DELETE FROM email_verification_issuance");
        jdbc.update("DELETE FROM email_verification_issuance_guard");
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM refresh_token_family");
        jdbc.update("DELETE FROM local_credential");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM external_provider_identity");
        jdbc.update("DELETE FROM user_access_revocation");
        jdbc.update("DELETE FROM user_account");
        when(otpGenerator.generate()).thenReturn(OTP);
        when(identityClock.instant()).thenReturn(NOW);
    }

    @Test
    void registerCreatesPendingAccountWithoutTokenOrSession() throws Exception {
        String username = username("registered");
        String email = email("registered");
        ResponseEntity<String> response = register(username, email, "registration-secret");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("VERIFICATION_REQUIRED", json(response).path("status").asText());
        assertEquals(
                "PENDING_EMAIL_VERIFICATION",
                text("SELECT account_status FROM user_account WHERE username = ?", username));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertNoCredentialResponse(response, "registration-secret");
    }

    @Test
    void testScopedSigningMaterialBootstrapsIssuerJwksAndResourceServer() throws Exception {
        UserAccountEntity user =
                persistLoginUser("bootstrap", "bootstrap-secret", AccountLifecycleStatus.ACTIVE, true, true);
        String token = accessTokenIssuer.issue(user.getId()).value();

        assertEquals(ISSUER, jwtDecoder.decode(token).getIssuer().toString());
        ResponseEntity<String> jwks = restTemplate.getForEntity("/oauth2/jwks", String.class);
        assertEquals(HttpStatus.OK, jwks.getStatusCode());
        assertEquals(KEY_ID, json(jwks).path("keys").get(0).path("kid").asText());
    }

    @Test
    void registrationValidationAndGmailPolicyFailuresAreSanitized() throws Exception {
        String password = "password-that-must-not-appear";
        ResponseEntity<String> malformed = register(username("malformed"), "not-a-gmail-address", password);
        ResponseEntity<String> nonGmail = register(username("nongmail"), "person@example.com", password);

        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertEquals("INVALID_REQUEST", json(malformed).path("code").asText());
        assertEquals(HttpStatus.BAD_REQUEST, nonGmail.getStatusCode());
        assertEquals("INVALID_REQUEST", json(nonGmail).path("code").asText());
        assertFalse(malformed.getBody().contains(password));
        assertFalse(nonGmail.getBody().contains(password));
    }

    @Test
    void usernameConflictIsActionableButDoesNotExposeOtherAccountData() throws Exception {
        String username = username("taken");
        register(username, email("first"), "first-registration-secret");
        ResponseEntity<String> response = register(username, email("second"), "second-registration-secret");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("AUTH_USERNAME_UNAVAILABLE", json(response).path("code").asText());
        assertFalse(response.getBody().contains("second-registration-secret"));
    }

    @Test
    void otherRegistrationConflictUsesTheGenericPublicCode() throws Exception {
        doReturn(LocalRegistrationResult.registrationFailed())
                .when(registrationService)
                .register(any());

        ResponseEntity<String> response = register(username("generic-conflict"), email("generic-conflict"), "secret");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("AUTH_REGISTRATION_FAILED", json(response).path("code").asText());
        assertFalse(response.getBody().contains("generic-conflict"));
    }

    @Test
    void verificationRequestCommitsChallengeIssuanceAndEncryptedOutbox() throws Exception {
        String username = registerPending("request-real", email("request-real"));

        ResponseEntity<String> response = requestVerification(username);

        assertAcceptedRequest(response);
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_challenge"));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_issuance"));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_email_outbox"));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE user_id IS NOT NULL"));
        assertFalse(text("SELECT encode(ciphertext, 'hex') FROM email_verification_email_outbox LIMIT 1")
                .contains(OTP.value()));
    }

    @Test
    void unknownAndIneligibleVerificationRequestsArePubliclyIndistinguishableNoOps() throws Exception {
        ResponseEntity<String> unknown = requestVerification("unknown-" + UUID.randomUUID());
        assertAcceptedRequest(unknown);
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_email_outbox"));

        String username = registerPending("ineligible", email("ineligible"));
        jdbc.update("UPDATE user_account SET account_status = 'DISABLED' WHERE username = ?", username);
        ResponseEntity<String> ineligible = requestVerification(username);
        assertAcceptedRequest(ineligible);
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_challenge"));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_issuance"));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_email_outbox"));
        assertEquals(
                json(unknown).path("status").asText(),
                json(ineligible).path("status").asText());
    }

    @Test
    void cooldownAndRollingHourlySuppressionRemainGenericNoOps() throws Exception {
        String username = registerPending("cooldown", email("cooldown"));
        assertAcceptedRequest(requestVerification(username));
        assertAcceptedRequest(requestVerification(username));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_issuance"));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_email_outbox"));

        resetState();
        String sharedEmail = email("hourly");
        List<String> usernames = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> registerPending("hourly-" + index, sharedEmail))
                .toList();
        for (String current : usernames) {
            assertAcceptedRequest(requestVerification(current));
        }
        assertEquals(5L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", sharedEmail));
        assertEquals(
                5L,
                count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", sharedEmail));
    }

    @Test
    void alreadyVerifiedRequestIsARespondsWithTheSameGenericAcceptance() throws Exception {
        String username = registerPending("already-verified", email("already-verified"));
        requestVerification(username);
        assertEquals(
                HttpStatus.NO_CONTENT,
                confirmVerification(username, OTP.value()).getStatusCode());

        ResponseEntity<String> response = requestVerification(username);

        assertAcceptedRequest(response);
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_issuance"));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_email_outbox"));
    }

    @Test
    void losingPendingAccountAfterEmailOwnershipUsesGenericNoOpWithoutNewIssuance() throws Exception {
        String sharedEmail = email("owned-shared");
        String owner = registerPending("owned-owner", sharedEmail);
        String losing = registerPending("owned-losing", sharedEmail);
        assertAcceptedRequest(requestVerification(owner));
        assertEquals(
                HttpStatus.NO_CONTENT, confirmVerification(owner, OTP.value()).getStatusCode());
        long historyBefore = count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", sharedEmail);
        long outboxBefore =
                count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", sharedEmail);

        ResponseEntity<String> response = requestVerification(losing);

        assertAcceptedRequest(response);
        assertEquals(
                0L,
                count(
                        "SELECT COUNT(*) FROM email_verification_challenge challenge "
                                + "JOIN user_account account ON account.id = challenge.user_id WHERE account.username = ?",
                        losing));
        assertEquals(
                historyBefore, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", sharedEmail));
        assertEquals(
                outboxBefore,
                count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", sharedEmail));
    }

    @Test
    void successfulConfirmationActivatesAccountWithoutTokenOrSession() throws Exception {
        String username = registerPending("confirm-success", email("confirm-success"));
        requestVerification(username);

        ResponseEntity<String> response = confirmVerification(username, OTP.value());

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertTrue(response.getBody() == null || response.getBody().isEmpty());
        assertEquals("ACTIVE", text("SELECT account_status FROM user_account WHERE username = ?", username));
        assertNotNull(value("SELECT email_verified_at FROM user_account WHERE username = ?", username));
        assertEquals(
                1L,
                count(
                        "SELECT COUNT(*) FROM user_role role JOIN user_account account ON account.id = role.user_id "
                                + "WHERE account.username = ? AND role.role = 'STUDENT'",
                        username));
        assertNoCredentialResponse(response, OTP.value());
    }

    @Test
    void everyExpectedVerificationFailureUsesTheSamePublicError() throws Exception {
        String wrong = registerPending("wrong", email("wrong"));
        requestVerification(wrong);
        assertVerificationFailure(confirmVerification(wrong, "999999"));

        String expired = registerPending("expired", email("expired"));
        requestVerification(expired);
        when(identityClock.instant()).thenReturn(NOW.plusSeconds(601));
        assertVerificationFailure(confirmVerification(expired, OTP.value()));
        when(identityClock.instant()).thenReturn(NOW);

        String exhausted = registerPending("exhausted", email("exhausted"));
        requestVerification(exhausted);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertVerificationFailure(confirmVerification(exhausted, "999999"));
        }
        assertVerificationFailure(confirmVerification(exhausted, OTP.value()));

        String noChallenge = registerPending("no-challenge", email("no-challenge"));
        assertVerificationFailure(confirmVerification(noChallenge, OTP.value()));
        assertVerificationFailure(confirmVerification("unknown-" + UUID.randomUUID(), OTP.value()));
    }

    @Test
    void unknownConfirmationUsesDummyHashWorkWithoutCreatingVerificationState() throws Exception {
        ResponseEntity<String> response = confirmVerification("unknown-" + UUID.randomUUID(), OTP.value());

        assertVerificationFailure(response);
        verify(verificationTimingProtector)
                .balanceConfirmation(argThat(candidate -> OTP.value().equals(candidate.value())));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_challenge"));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_issuance"));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_email_outbox"));
    }

    @Test
    void ownershipConflictAndConsumedChallengeDoNotExposeTheirReasons() throws Exception {
        String sharedEmail = email("ownership");
        String first = registerPending("owner-a", sharedEmail);
        String second = registerPending("owner-b", sharedEmail);
        requestVerification(first);
        requestVerification(second);
        assertEquals(
                HttpStatus.NO_CONTENT, confirmVerification(first, OTP.value()).getStatusCode());

        ResponseEntity<String> ownershipConflict = confirmVerification(second, OTP.value());
        ResponseEntity<String> consumed = confirmVerification(first, OTP.value());

        assertVerificationFailure(ownershipConflict);
        assertVerificationFailure(consumed);
        assertFalse(ownershipConflict.getBody().contains(first));
        assertFalse(ownershipConflict.getBody().contains(sharedEmail));
    }

    @Test
    void loginReturnsRealAccessJwtAndOnePersistentLocalRefreshCookie() throws Exception {
        String password = "http-login-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("http-login", password, AccountLifecycleStatus.ACTIVE, true, true);

        ResponseEntity<String> response = login(user.getUsername(), password);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = json(response);
        String accessToken = body.path("accessToken").asText();
        SignedJWT jwt = SignedJWT.parse(accessToken);
        assertEquals("Bearer", body.path("tokenType").asText());
        assertEquals(
                Duration.between(NOW, jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                        .toSeconds(),
                body.path("expiresIn").asLong());
        assertTrue(body.path("expiresIn").asLong() > 0);
        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
        assertEquals(KEY_ID, jwt.getHeader().getKeyID());
        assertEquals(ISSUER, jwt.getJWTClaimsSet().getIssuer());
        assertEquals(user.getId().toString(), jwt.getJWTClaimsSet().getSubject());
        assertEquals(
                QuizopiaTokenClaims.USER, jwt.getJWTClaimsSet().getStringClaim(QuizopiaTokenClaims.PRINCIPAL_TYPE));
        assertEquals(Set.of("STUDENT"), Set.copyOf(jwt.getJWTClaimsSet().getStringListClaim("roles")));

        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookies);
        assertEquals(1, setCookies.size());
        String setCookie = setCookies.getFirst();
        String rawRefresh = setCookie.substring("quizopia_refresh=".length(), setCookie.indexOf(';'));
        assertFalse(rawRefresh.isBlank());
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("Path=/api/auth"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertFalse(setCookie.contains("Secure"));
        assertFalse(setCookie.toLowerCase(java.util.Locale.ROOT).contains("domain="));
        Instant familyExpiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM refresh_token_family WHERE user_id = ?", Instant.class, user.getId());
        long maxAge = Long.parseLong(setCookie.replaceFirst(".*Max-Age=([0-9]+).*", "$1"));
        assertEquals(Duration.between(NOW, familyExpiresAt).toSeconds(), maxAge);
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", user.getId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertFalse(text("SELECT encode(token_hash, 'hex') FROM refresh_token").contains(rawRefresh));
        assertFalse(response.getBody().contains(rawRefresh));
        assertFalse(response.getBody().contains(password));
        assertFalse(response.getBody().contains(user.getId().toString()));
        assertFalse(body.has("refreshToken"));
        assertFalse(body.has("familyId"));
        assertFalse(body.has("credentialId"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void exactVerifiedEmailCanLogin() throws Exception {
        String password = "email-http-login-secret-" + UUID.randomUUID();
        UserAccountEntity user =
                persistLoginUser("email-http-login", password, AccountLifecycleStatus.ACTIVE, true, true);

        ResponseEntity<String> response = login(user.getEmail(), password);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(json(response).path("accessToken").asText().isBlank());
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", user.getId()));
    }

    @Test
    void invalidCredentialsAndIneligibleAccountsShareOnePublicFailureWithoutSessions() throws Exception {
        String password = "http-invalid-secret-" + UUID.randomUUID();
        UserAccountEntity eligible =
                persistLoginUser("eligible-http", password, AccountLifecycleStatus.ACTIVE, true, true);
        UserAccountEntity pending = persistLoginUser(
                "pending-http", password, AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION, false, false);

        ResponseEntity<String> wrong = login(eligible.getUsername(), "wrong-" + password);
        ResponseEntity<String> unknown = login("unknown-" + UUID.randomUUID(), password);
        ResponseEntity<String> ineligible = login(pending.getUsername(), password);

        assertInvalidCredentials(wrong, password);
        assertInvalidCredentials(unknown, password);
        assertInvalidCredentials(ineligible, password);
        assertEquals(wrong.getBody(), unknown.getBody());
        assertEquals(wrong.getBody(), ineligible.getBody());
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void malformedLoginUsesValidationEnvelopeWithoutEchoingPassword() throws Exception {
        String password = "malformed-http-password";

        ResponseEntity<String> response = login("", password);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_REQUEST", json(response).path("code").asText());
        assertFalse(response.getBody().contains(password));
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    @Test
    void jwtFailureReturnsNoTokenOrCookieAndLeavesNoRefreshState() {
        String password = "http-jwt-failure-secret-" + UUID.randomUUID();
        UserAccountEntity user =
                persistLoginUser("http-jwt-failure", password, AccountLifecycleStatus.ACTIVE, true, true);
        doThrow(new UserAccessTokenIssuanceException()).when(accessTokenIssuer).issue(user.getId());

        ResponseEntity<String> response = login(user.getUsername(), password);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertFalse(response.getBody() != null && response.getBody().contains(password));
        assertFalse(response.getBody() != null && response.getBody().contains("accessToken"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void refreshRotatesCookieAndJwtWithoutExtendingTheOriginalFamily() throws Exception {
        String password = "http-refresh-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("http-refresh", password, AccountLifecycleStatus.ACTIVE, true, true);
        ResponseEntity<String> login = login(user.getUsername(), password);
        String credentialA = refreshCookieValue(login);
        UUID familyId =
                jdbc.queryForObject("SELECT id FROM refresh_token_family WHERE user_id = ?", UUID.class, user.getId());
        Instant familyExpiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM refresh_token_family WHERE id = ?", Instant.class, familyId);
        Instant refreshTime = NOW.plus(Duration.ofDays(2));
        when(identityClock.instant()).thenReturn(refreshTime);

        ResponseEntity<String> response = refresh(credentialA, TRUSTED_ORIGIN);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String credentialB = refreshCookieValue(response);
        assertNotEquals(credentialA, credentialB);
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(
                familyExpiresAt,
                jdbc.queryForObject(
                        "SELECT expires_at FROM refresh_token_family WHERE id = ?", Instant.class, familyId));
        assertEquals(2L, count("SELECT COUNT(*) FROM refresh_token"));
        assertNotNull(jdbc.queryForObject(
                "SELECT consumed_at FROM refresh_token WHERE token_hash = ?",
                Instant.class,
                refreshCredentialHasher.hash(RawRefreshCredential.from(credentialA))));
        assertEquals(
                1L,
                count(
                        "SELECT COUNT(*) FROM refresh_token WHERE token_hash = ? AND consumed_at IS NULL",
                        refreshCredentialHasher.hash(RawRefreshCredential.from(credentialB))));
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("Path=/api/auth"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertFalse(setCookie.contains("Secure"));
        assertFalse(setCookie.toLowerCase(java.util.Locale.ROOT).contains("domain="));
        long maxAge = Long.parseLong(setCookie.replaceFirst(".*Max-Age=([0-9]+).*", "$1"));
        assertEquals(Duration.between(refreshTime, familyExpiresAt).toSeconds(), maxAge);
        assertTrue(maxAge < Duration.ofDays(7).toSeconds());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getFirst(HttpHeaders.PRAGMA));
        JsonNode body = json(response);
        SignedJWT jwt = SignedJWT.parse(body.path("accessToken").asText());
        assertEquals("Bearer", body.path("tokenType").asText());
        assertEquals(
                Duration.between(
                                refreshTime,
                                jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                        .toSeconds(),
                body.path("expiresIn").asLong());
        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
        assertEquals(KEY_ID, jwt.getHeader().getKeyID());
        assertEquals(ISSUER, jwt.getJWTClaimsSet().getIssuer());
        assertEquals(user.getId().toString(), jwt.getJWTClaimsSet().getSubject());
        assertFalse(response.getBody().contains(credentialA));
        assertFalse(response.getBody().contains(credentialB));
        assertFalse(text("SELECT encode(token_hash, 'hex') FROM refresh_token WHERE consumed_at IS NULL")
                .contains(credentialB));
    }

    @Test
    void refreshWithSubsecondFamilyLifetimeFailsBeforeRotationAndEmitsNoCookie() throws Exception {
        String password = "subsecond-refresh-secret-" + UUID.randomUUID();
        UserAccountEntity user =
                persistLoginUser("subsecond-refresh", password, AccountLifecycleStatus.ACTIVE, true, true);
        ResponseEntity<String> login = login(user.getUsername(), password);
        String credential = refreshCookieValue(login);
        assertEquals(
                1,
                jdbc.update(
                        "UPDATE refresh_token_family SET created_at = ?, expires_at = ? WHERE user_id = ?",
                        java.sql.Timestamp.from(NOW.minusSeconds(1)),
                        java.sql.Timestamp.from(NOW.plusMillis(500)),
                        user.getId()));

        ResponseEntity<String> response = refresh(credential, TRUSTED_ORIGIN);

        assertRefreshFailed(response);
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM refresh_token", Instant.class));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void everyInvalidRefreshStateUsesOneGenericFailureWithoutReplacementCookie() throws Exception {
        ResponseEntity<String> missing = refresh(null, TRUSTED_ORIGIN);
        ResponseEntity<String> blank = refreshRawCookie("quizopia_refresh=", TRUSTED_ORIGIN);
        ResponseEntity<String> malformed = refresh("malformed", TRUSTED_ORIGIN);
        ResponseEntity<String> unknown = refresh("A".repeat(43), TRUSTED_ORIGIN);

        UserAccountEntity expiredUser =
                persistLoginUser("expired-refresh", "secret", AccountLifecycleStatus.ACTIVE, true, true);
        String expiredCredential = refreshCookieValue(login(expiredUser.getUsername(), "secret"));
        when(identityClock.instant()).thenReturn(NOW.plus(Duration.ofDays(8)));
        ResponseEntity<String> expired = refresh(expiredCredential, TRUSTED_ORIGIN);
        when(identityClock.instant()).thenReturn(NOW);

        UserAccountEntity revokedUser =
                persistLoginUser("revoked-refresh", "secret", AccountLifecycleStatus.ACTIVE, true, true);
        String revokedCredential = refreshCookieValue(login(revokedUser.getUsername(), "secret"));
        jdbc.update(
                "UPDATE refresh_token_family SET revoked_at = ? WHERE user_id = ?",
                Timestamp.from(NOW),
                revokedUser.getId());
        ResponseEntity<String> revoked = refresh(revokedCredential, TRUSTED_ORIGIN);

        UserAccountEntity disabledUser =
                persistLoginUser("disabled-refresh", "secret", AccountLifecycleStatus.ACTIVE, true, true);
        String disabledCredential = refreshCookieValue(login(disabledUser.getUsername(), "secret"));
        jdbc.update("UPDATE user_account SET account_status = 'DISABLED' WHERE id = ?", disabledUser.getId());
        ResponseEntity<String> disabled = refresh(disabledCredential, TRUSTED_ORIGIN);

        UserAccountEntity cutoffUser =
                persistLoginUser("cutoff-refresh", "secret", AccountLifecycleStatus.ACTIVE, true, true);
        String cutoffCredential = refreshCookieValue(login(cutoffUser.getUsername(), "secret"));
        jdbc.update(
                "INSERT INTO user_access_revocation (user_id, revoked_before) VALUES (?, ?)",
                cutoffUser.getId(),
                Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")));
        ResponseEntity<String> cutoff = refresh(cutoffCredential, TRUSTED_ORIGIN);

        String expectedBody = null;
        for (ResponseEntity<String> response :
                List.of(missing, blank, malformed, unknown, expired, revoked, disabled, cutoff)) {
            assertRefreshFailed(response);
            if (expectedBody == null) {
                expectedBody = response.getBody();
            } else {
                assertEquals(expectedBody, response.getBody());
            }
        }
    }

    @Test
    void refreshAcceptsCredentialOnlyFromTheExactCookie() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
        headers.set(HttpHeaders.AUTHORIZATION, "Refresh " + "B".repeat(43));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                AuthController.REFRESH_PATH,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", "C".repeat(43)), headers),
                String.class);

        assertRefreshFailed(response);
        assertRefreshFailed(refreshRawCookie(
                "quizopia_refresh=" + "A".repeat(43) + "; quizopia_refresh=" + "B".repeat(43), TRUSTED_ORIGIN));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void replayRevokesFamilyAndMakesReplacementUnusable() throws Exception {
        String password = "replay-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("replay", password, AccountLifecycleStatus.ACTIVE, true, true);
        String credentialA = refreshCookieValue(login(user.getUsername(), password));
        String credentialB = refreshCookieValue(refresh(credentialA, TRUSTED_ORIGIN));

        ResponseEntity<String> replay = refresh(credentialA, TRUSTED_ORIGIN);
        ResponseEntity<String> replacement = refresh(credentialB, TRUSTED_ORIGIN);

        assertRefreshFailed(replay);
        assertRefreshFailed(replacement);
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NOT NULL"));
    }

    @Test
    void jwtFailureRollsBackRefreshAndLeavesPreviousCookieUsable() {
        String password = "refresh-jwt-failure-" + UUID.randomUUID();
        UserAccountEntity user =
                persistLoginUser("refresh-jwt-failure", password, AccountLifecycleStatus.ACTIVE, true, true);
        String credentialA = refreshCookieValue(login(user.getUsername(), password));
        doThrow(new UserAccessTokenIssuanceException()).when(accessTokenIssuer).issue(user.getId());

        ResponseEntity<String> failed = refresh(credentialA, TRUSTED_ORIGIN);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatusCode());
        assertNull(failed.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertFalse(failed.getBody() != null && failed.getBody().contains("accessToken"));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token WHERE consumed_at IS NOT NULL"));
        doCallRealMethod().when(accessTokenIssuer).issue(user.getId());
        assertEquals(HttpStatus.OK, refresh(credentialA, TRUSTED_ORIGIN).getStatusCode());
    }

    @Test
    void originRejectionsOccurBeforeRotationAndDoNotConsumeValidCookie() throws Exception {
        String password = "origin-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("origin", password, AccountLifecycleStatus.ACTIVE, true, true);
        String credentialA = refreshCookieValue(login(user.getUsername(), password));
        clearInvocations(accessTokenIssuer);

        List<ResponseEntity<String>> rejected = List.of(
                refresh(credentialA, null),
                refresh(credentialA, "https://attacker.example"),
                refresh(credentialA, "null"),
                refresh(credentialA, TRUSTED_ORIGIN + ".attacker.example"),
                refreshWithDuplicateOrigins(credentialA));

        for (ResponseEntity<String> response : rejected) {
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertEquals("ACCESS_DENIED", json(response).path("code").asText());
            assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        }
        verify(accessTokenIssuer, never()).issue(user.getId());
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token WHERE consumed_at IS NOT NULL"));
        assertEquals(HttpStatus.OK, refresh(credentialA, TRUSTED_ORIGIN).getStatusCode());
    }

    @Test
    void logoutRevokesOnlyCurrentFamilyClearsCookieAndLeavesAccessJwtUsable() throws Exception {
        String password = "logout-secret-" + UUID.randomUUID();
        UserAccountEntity user =
                persistLoginUser("logout-current", password, AccountLifecycleStatus.ACTIVE, true, true);
        ResponseEntity<String> login = login(user.getUsername(), password);
        String accessToken = json(login).path("accessToken").asText();
        String credential = refreshCookieValue(login);

        ResponseEntity<String> logout = logout(credential, TRUSTED_ORIGIN);

        assertEquals(HttpStatus.NO_CONTENT, logout.getStatusCode());
        assertTrue(logout.getBody() == null || logout.getBody().isEmpty());
        assertClearingCookie(logout, credential, false);
        assertNotNull(value("SELECT revoked_at FROM refresh_token_family WHERE user_id = ?", user.getId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token WHERE consumed_at IS NOT NULL"));
        assertRefreshFailed(refresh(credential, TRUSTED_ORIGIN));
        assertEquals(HttpStatus.OK, me(accessToken).getStatusCode());
        assertEquals(
                0L,
                count(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_name IN ('access_token', 'access_token_blacklist')"));
    }

    @Test
    void logoutRevokesOnlyThePresentedFamilyWhenUserHasMultipleBrowserSessions() throws Exception {
        String password = "logout-multi-session-secret-" + UUID.randomUUID();
        UserAccountEntity user =
                persistLoginUser("logout-multi-session", password, AccountLifecycleStatus.ACTIVE, true, true);
        String credentialA = refreshCookieValue(login(user.getUsername(), password));
        String credentialB = refreshCookieValue(login(user.getUsername(), password));
        assertEquals(2L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", user.getId()));

        ResponseEntity<String> logout = logout(credentialA, TRUSTED_ORIGIN);

        assertEquals(HttpStatus.NO_CONTENT, logout.getStatusCode());
        assertClearingCookie(logout, credentialA, false);
        assertRefreshFailed(refresh(credentialA, TRUSTED_ORIGIN));
        assertEquals(HttpStatus.OK, refresh(credentialB, TRUSTED_ORIGIN).getStatusCode());
        assertEquals(2L, count("SELECT COUNT(*) FROM refresh_token_family WHERE user_id = ?", user.getId()));
    }

    @Test
    void logoutMissingMalformedUnknownExpiredAndRevokedCredentialsAreIndistinguishable() {
        List<ResponseEntity<String>> stateless = List.of(
                logoutRawCookie(null, TRUSTED_ORIGIN),
                logoutRawCookie("quizopia_refresh=", TRUSTED_ORIGIN),
                logoutRawCookie("quizopia_refresh=malformed", TRUSTED_ORIGIN),
                logout("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", TRUSTED_ORIGIN),
                logoutRawCookie(
                        "quizopia_refresh=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA; "
                                + "quizopia_refresh=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                        TRUSTED_ORIGIN));
        for (ResponseEntity<String> response : stateless) {
            assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
            assertClearingCookie(response, null, false);
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token"));

        String password = "logout-state-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("logout-state", password, AccountLifecycleStatus.ACTIVE, true, true);
        String credential = refreshCookieValue(login(user.getUsername(), password));
        when(identityClock.instant()).thenReturn(NOW.plus(Duration.ofDays(8)));
        ResponseEntity<String> expired = logout(credential, TRUSTED_ORIGIN);
        assertEquals(HttpStatus.NO_CONTENT, expired.getStatusCode());
        assertClearingCookie(expired, credential, false);
        assertNull(value("SELECT revoked_at FROM refresh_token_family WHERE user_id = ?", user.getId()));

        jdbc.update(
                "UPDATE refresh_token_family SET revoked_at = ? WHERE user_id = ?",
                Timestamp.from(NOW.plusSeconds(1)),
                user.getId());
        ResponseEntity<String> revoked = logout(credential, TRUSTED_ORIGIN);
        assertEquals(HttpStatus.NO_CONTENT, revoked.getStatusCode());
        assertClearingCookie(revoked, credential, false);
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void unexpectedLogoutPersistenceFailureDoesNotClaimSuccessOrClearCookie() {
        String password = "logout-failure-secret-" + UUID.randomUUID();
        UserAccountEntity user =
                persistLoginUser("logout-failure", password, AccountLifecycleStatus.ACTIVE, true, true);
        String credential = refreshCookieValue(login(user.getUsername(), password));
        doThrow(new IllegalStateException("forced sanitized logout failure"))
                .when(logoutService)
                .logout(any());

        ResponseEntity<String> response = logout(credential, TRUSTED_ORIGIN);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertNull(value("SELECT revoked_at FROM refresh_token_family WHERE user_id = ?", user.getId()));
        assertFalse(response.getBody() != null && response.getBody().contains(credential));
    }

    @Test
    void logoutWithConsumedCredentialRevokesFamilyAndInvalidatesReplacement() throws Exception {
        String password = "logout-replay-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("logout-replay", password, AccountLifecycleStatus.ACTIVE, true, true);
        String credentialA = refreshCookieValue(login(user.getUsername(), password));
        when(identityClock.instant()).thenReturn(NOW.plusSeconds(1));
        String credentialB = refreshCookieValue(refresh(credentialA, TRUSTED_ORIGIN));
        when(identityClock.instant()).thenReturn(NOW.plusSeconds(2));

        ResponseEntity<String> logout = logout(credentialA, TRUSTED_ORIGIN);

        assertEquals(HttpStatus.NO_CONTENT, logout.getStatusCode());
        assertClearingCookie(logout, credentialA, false);
        assertNotNull(value("SELECT revoked_at FROM refresh_token_family WHERE user_id = ?", user.getId()));
        assertRefreshFailed(refresh(credentialB, TRUSTED_ORIGIN));
    }

    @Test
    void logoutOriginRejectionsPrecedeMutationAndCookieClearing() throws Exception {
        String password = "logout-origin-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("logout-origin", password, AccountLifecycleStatus.ACTIVE, true, true);
        String credential = refreshCookieValue(login(user.getUsername(), password));

        List<ResponseEntity<String>> rejected = List.of(
                logout(credential, null),
                logout(credential, "https://attacker.example"),
                logout(credential, "null"),
                logout(credential, TRUSTED_ORIGIN + ".attacker.example"),
                logoutWithDuplicateOrigins(credential));

        for (ResponseEntity<String> response : rejected) {
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertEquals("ACCESS_DENIED", json(response).path("code").asText());
            assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        }
        assertNull(value("SELECT revoked_at FROM refresh_token_family WHERE user_id = ?", user.getId()));
        assertEquals(HttpStatus.OK, refresh(credential, TRUSTED_ORIGIN).getStatusCode());
    }

    @Test
    void meReturnsAuthoritativeProfileAndCurrentRolesWithoutSessionOrPersonaData() throws Exception {
        String password = "me-profile-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("me-profile", password, AccountLifecycleStatus.ACTIVE, true, true);
        String accessToken =
                json(login(user.getUsername(), password)).path("accessToken").asText();
        userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.TEACHER));

        ResponseEntity<String> response = me(accessToken);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = json(response);
        assertEquals(user.getId().toString(), body.path("id").asText());
        assertEquals(user.getUsername(), body.path("username").asText());
        assertEquals(user.getEmail(), body.path("email").asText());
        assertEquals(List.of("STUDENT", "TEACHER"), objectMapper.convertValue(body.path("roles"), List.class));
        for (String absent : List.of(
                "persona", "workspace", "refresh", "session", "accessToken", "tokenType", "expiresIn", "password")) {
            assertFalse(body.has(absent), absent);
        }
    }

    @Test
    void meRejectsDisabledRevokedIneligibleAndMissingAccountsWithoutStaleProfile() throws Exception {
        String password = "me-denied-secret-" + UUID.randomUUID();
        UserAccountEntity disabled =
                persistLoginUser("me-disabled", password, AccountLifecycleStatus.ACTIVE, true, true);
        String disabledToken = json(login(disabled.getUsername(), password))
                .path("accessToken")
                .asText();
        disabled.setAccountStatus("DISABLED");
        userAccountRepository.saveAndFlush(disabled);
        assertAccessDenied(me(disabledToken));

        UserAccountEntity revoked =
                persistLoginUser("me-revoked", password + "-revoked", AccountLifecycleStatus.ACTIVE, true, true);
        String revokedToken = json(login(revoked.getUsername(), password + "-revoked"))
                .path("accessToken")
                .asText();
        jdbc.update(
                "INSERT INTO user_access_revocation (user_id, revoked_before) VALUES (?, ?)",
                revoked.getId(),
                Timestamp.from(NOW));
        assertAccessDenied(me(revokedToken));

        UserAccountEntity ineligible =
                persistLoginUser("me-ineligible", password + "-ineligible", AccountLifecycleStatus.ACTIVE, true, true);
        String ineligibleToken = json(login(ineligible.getUsername(), password + "-ineligible"))
                .path("accessToken")
                .asText();
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", ineligible.getId());
        assertAccessDenied(me(ineligibleToken));

        assertAccessDenied(me(signedUserToken(UUID.randomUUID(), NOW, List.of("STUDENT"))));
    }

    @Test
    void meRejectsServiceClientMissingAndInvalidBearerAuthentication() throws Exception {
        assertEquals(HttpStatus.FORBIDDEN, me(signedServiceToken()).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, me(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, me("not-a-jwt").getStatusCode());
    }

    @Test
    void meRejectsCorrectlySignedExpiredUserToken() {
        String expiredToken = signedUserToken(
                UUID.randomUUID(), NOW.minus(USER_ACCESS_TOKEN_TTL).minus(Duration.ofMinutes(2)), List.of("STUDENT"));

        assertEquals(HttpStatus.UNAUTHORIZED, me(expiredToken).getStatusCode());
    }

    @Test
    void publicAuthPostsIgnoreMalformedAndExpiredBearerWhileMeRemainsProtected() throws Exception {
        String password = "public-bearer-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistLoginUser("public-bearer", password, AccountLifecycleStatus.ACTIVE, true, true);
        String expiredToken = signedUserToken(
                user.getId(), NOW.minus(USER_ACCESS_TOKEN_TTL).minus(Duration.ofMinutes(2)), List.of("STUDENT"));

        assertEquals(
                HttpStatus.OK,
                loginWithBearer(user.getUsername(), password, "malformed").getStatusCode());
        ResponseEntity<String> initialLogin = loginWithBearer(user.getUsername(), password, expiredToken);
        assertEquals(HttpStatus.OK, initialLogin.getStatusCode());

        String initialCredential = refreshCookieValue(initialLogin);
        ResponseEntity<String> refreshed = refreshWithBearer(initialCredential, TRUSTED_ORIGIN, expiredToken);
        assertEquals(HttpStatus.OK, refreshed.getStatusCode());
        String replacementCredential = refreshCookieValue(refreshed);
        assertEquals(
                HttpStatus.NO_CONTENT,
                logoutWithBearer(replacementCredential, TRUSTED_ORIGIN, expiredToken)
                        .getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, me("malformed").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, me(expiredToken).getStatusCode());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("malformed");
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .exchange("/api/auth/foo", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                        .getStatusCode());
    }

    @Test
    void meRejectsCorrectlySignedTokenFromWrongIssuer() {
        UserAccountEntity user = persistLoginUser("wrong-issuer", "secret", AccountLifecycleStatus.ACTIVE, true, true);
        String token = signedUserToken(user.getId(), NOW, List.of("STUDENT"), "https://wrong-issuer.example");
        assertEquals(HttpStatus.UNAUTHORIZED, me(token).getStatusCode());
    }

    @Test
    void anonymousSecurityIsLimitedToTheSixExactPostRoutesAndMeRequiresUserBearer() {
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .getForEntity(AuthController.REGISTER_PATH, String.class)
                        .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, login("", "").getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .getForEntity(AuthController.LOGIN_PATH, String.class)
                        .getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .exchange(AuthController.LOGIN_PATH, HttpMethod.PUT, jsonEntity(Map.of()), String.class)
                        .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, refresh(null, TRUSTED_ORIGIN).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, refresh(null, null).getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .getForEntity(AuthController.REFRESH_PATH, String.class)
                        .getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .exchange(AuthController.REFRESH_PATH, HttpMethod.PUT, jsonEntity(Map.of()), String.class)
                        .getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, logout(null, TRUSTED_ORIGIN).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, logout(null, null).getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .getForEntity(AuthController.LOGOUT_PATH, String.class)
                        .getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .exchange(AuthController.LOGOUT_PATH, HttpMethod.PUT, jsonEntity(Map.of()), String.class)
                        .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, me(null).getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .exchange(AuthController.ME_PATH, HttpMethod.POST, jsonEntity(Map.of()), String.class)
                        .getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .exchange("/api/auth/foo", HttpMethod.POST, jsonEntity(Map.of()), String.class)
                        .getStatusCode());
    }

    @Test
    void generatedOpenApiContainsOnlyTheCurrentAuthTransportContract() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode paths = json(response).path("paths");
        assertTrue(paths.path(AuthController.REGISTER_PATH).has("post"));
        assertTrue(paths.path(AuthController.REQUEST_PATH).has("post"));
        assertTrue(paths.path(AuthController.CONFIRM_PATH).has("post"));
        assertTrue(paths.path(AuthController.LOGIN_PATH).has("post"));
        assertTrue(paths.path(AuthController.REFRESH_PATH).has("post"));
        assertTrue(paths.path(AuthController.LOGOUT_PATH).has("post"));
        assertTrue(paths.path(AuthController.ME_PATH).has("get"));
        assertFalse(paths.path(AuthController.REGISTER_PATH).has("get"));
        assertFalse(paths.path(AuthController.LOGIN_PATH).has("get"));
        assertFalse(paths.path(AuthController.LOGOUT_PATH).has("get"));
        assertFalse(paths.path(AuthController.ME_PATH).has("post"));
        assertEquals(
                true,
                paths.path(AuthController.REGISTER_PATH)
                        .path("post")
                        .path("responses")
                        .has("202"));
        assertEquals(
                true,
                paths.path(AuthController.REQUEST_PATH)
                        .path("post")
                        .path("responses")
                        .has("202"));
        assertTrue(paths.path(AuthController.REQUEST_PATH)
                .path("post")
                .path("responses")
                .has("400"));
        assertEquals(
                true,
                paths.path(AuthController.CONFIRM_PATH)
                        .path("post")
                        .path("responses")
                        .has("204"));
        assertTrue(paths.path(AuthController.LOGIN_PATH)
                .path("post")
                .path("responses")
                .has("200"));
        assertTrue(paths.path(AuthController.LOGIN_PATH)
                .path("post")
                .path("responses")
                .has("400"));
        assertTrue(paths.path(AuthController.LOGIN_PATH)
                .path("post")
                .path("responses")
                .has("401"));
        assertTrue(paths.path(AuthController.REFRESH_PATH)
                .path("post")
                .path("responses")
                .has("200"));
        assertTrue(paths.path(AuthController.REFRESH_PATH)
                .path("post")
                .path("responses")
                .has("401"));
        assertTrue(paths.path(AuthController.REFRESH_PATH)
                .path("post")
                .path("responses")
                .has("403"));
        assertTrue(paths.path(AuthController.LOGOUT_PATH)
                .path("post")
                .path("responses")
                .has("204"));
        assertTrue(paths.path(AuthController.LOGOUT_PATH)
                .path("post")
                .path("responses")
                .has("403"));
        assertTrue(
                paths.path(AuthController.ME_PATH).path("get").path("responses").has("200"));
        assertTrue(
                paths.path(AuthController.ME_PATH).path("get").path("responses").has("401"));
        assertTrue(
                paths.path(AuthController.ME_PATH).path("get").path("responses").has("403"));
        assertTrue(paths.path(AuthController.ME_PATH)
                .path("get")
                .path("security")
                .get(0)
                .propertyNames()
                .contains("bearerAuth"));
        String document = response.getBody();
        assertTrue(document.contains("AUTH_USERNAME_UNAVAILABLE"));
        assertTrue(document.contains("AUTH_REGISTRATION_FAILED"));
        assertTrue(document.contains("AUTH_VERIFICATION_FAILED"));
        assertTrue(document.contains("AUTH_INVALID_CREDENTIALS"));
        assertTrue(document.contains("AUTH_REFRESH_FAILED"));
        assertTrue(document.contains("ACCESS_DENIED"));
        assertFalse(document.contains("EmailVerificationStatus"));
    }

    private String registerPending(String prefix, String email) {
        String username = username(prefix);
        ResponseEntity<String> response = register(username, email, "registration-secret-" + UUID.randomUUID());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        return username;
    }

    private ResponseEntity<String> register(String username, String email, String password) {
        return post(AuthController.REGISTER_PATH, Map.of("username", username, "email", email, "password", password));
    }

    private ResponseEntity<String> requestVerification(String username) {
        return post(AuthController.REQUEST_PATH, Map.of("username", username));
    }

    private ResponseEntity<String> confirmVerification(String username, String otp) {
        return post(AuthController.CONFIRM_PATH, Map.of("username", username, "otp", otp));
    }

    private ResponseEntity<String> login(String identifier, String password) {
        return post(AuthController.LOGIN_PATH, Map.of("identifier", identifier, "password", password));
    }

    private ResponseEntity<String> loginWithBearer(String identifier, String password, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return restTemplate.exchange(
                AuthController.LOGIN_PATH,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("identifier", identifier, "password", password), headers),
                String.class);
    }

    private ResponseEntity<String> refresh(String credential, String origin) {
        String cookie = credential == null ? null : "quizopia_refresh=" + credential;
        return refreshRawCookie(cookie, origin);
    }

    private ResponseEntity<String> refreshRawCookie(String cookie, String origin) {
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) {
            headers.add(HttpHeaders.ORIGIN, origin);
        }
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return restTemplate.exchange(
                AuthController.REFRESH_PATH, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> refreshWithBearer(String credential, String origin, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, origin);
        headers.add(HttpHeaders.COOKIE, "quizopia_refresh=" + credential);
        headers.setBearerAuth(bearer);
        return restTemplate.exchange(
                AuthController.REFRESH_PATH, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> refreshWithDuplicateOrigins(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
        headers.add(HttpHeaders.ORIGIN, "https://attacker.example");
        headers.add(HttpHeaders.COOKIE, "quizopia_refresh=" + credential);
        return restTemplate.exchange(
                AuthController.REFRESH_PATH, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> logout(String credential, String origin) {
        String cookie = credential == null ? null : "quizopia_refresh=" + credential;
        return logoutRawCookie(cookie, origin);
    }

    private ResponseEntity<String> logoutRawCookie(String cookie, String origin) {
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) {
            headers.add(HttpHeaders.ORIGIN, origin);
        }
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return restTemplate.exchange(
                AuthController.LOGOUT_PATH, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> logoutWithBearer(String credential, String origin, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, origin);
        headers.add(HttpHeaders.COOKIE, "quizopia_refresh=" + credential);
        headers.setBearerAuth(bearer);
        return restTemplate.exchange(
                AuthController.LOGOUT_PATH, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> logoutWithDuplicateOrigins(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
        headers.add(HttpHeaders.ORIGIN, "https://attacker.example");
        headers.add(HttpHeaders.COOKIE, "quizopia_refresh=" + credential);
        return restTemplate.exchange(
                AuthController.LOGOUT_PATH, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> me(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return restTemplate.exchange(AuthController.ME_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String signedUserToken(UUID userId, Instant issuedAt, List<String> roles) {
        return signedUserToken(userId, issuedAt, roles, ISSUER);
    }

    private String signedUserToken(UUID userId, Instant issuedAt, List<String> roles, String issuer) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(USER_ACCESS_TOKEN_TTL))
                .claim(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.USER)
                .claim(QuizopiaTokenClaims.ROLES, roles)
                .build();
        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims))
                .getTokenValue();
    }

    private String signedServiceToken() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("assessment-service")
                .issuedAt(NOW)
                .expiresAt(NOW.plus(USER_ACCESS_TOKEN_TTL))
                .claim(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.SERVICE)
                .claim(QuizopiaTokenClaims.SCOPE, List.of("classroom.membership.read"))
                .build();
        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims))
                .getTokenValue();
    }

    private ResponseEntity<String> post(String path, Map<String, String> body) {
        return restTemplate.exchange(path, HttpMethod.POST, jsonEntity(body), String.class);
    }

    private HttpEntity<Map<String, String>> jsonEntity(Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private void assertAcceptedRequest(ResponseEntity<String> response) throws Exception {
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(
                "VERIFICATION_REQUEST_ACCEPTED", json(response).path("status").asText());
        assertNoCredentialResponse(response, OTP.value());
    }

    private void assertVerificationFailure(ResponseEntity<String> response) throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("AUTH_VERIFICATION_FAILED", json(response).path("code").asText());
        assertFalse(response.getBody().contains(OTP.value()));
        assertFalse(response.getBody().contains("999999"));
    }

    private void assertInvalidCredentials(ResponseEntity<String> response, String password) throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("AUTH_INVALID_CREDENTIALS", json(response).path("code").asText());
        assertFalse(response.getBody().contains(password));
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    private void assertRefreshFailed(ResponseEntity<String> response) throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("AUTH_REFRESH_FAILED", json(response).path("code").asText());
        assertFalse(response.getBody().contains("accessToken"));
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    private void assertAccessDenied(ResponseEntity<String> response) throws Exception {
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACCESS_DENIED", json(response).path("code").asText());
        assertFalse(response.getBody().contains("USER_DISABLED"));
        assertFalse(response.getBody().contains("REVOCATION_CUTOFF"));
        assertFalse(response.getBody().contains("USER_NOT_FOUND"));
    }

    private void assertClearingCookie(ResponseEntity<String> response, String oldCredential, boolean secure) {
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("quizopia_refresh=;"));
        assertTrue(cookie.contains("Path=/api/auth"));
        assertTrue(cookie.contains("Max-Age=0"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertEquals(secure, cookie.contains("Secure"));
        assertFalse(cookie.contains("Domain="));
        if (oldCredential != null) {
            assertFalse(cookie.contains(oldCredential));
        }
    }

    private String refreshCookieValue(ResponseEntity<String> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        return setCookie.substring("quizopia_refresh=".length(), setCookie.indexOf(';'));
    }

    private UserAccountEntity persistLoginUser(
            String prefix, String password, String status, boolean verified, boolean student) {
        UserAccountEntity user = new UserAccountEntity(email(prefix), username(prefix));
        user.setAccountStatus(status);
        user.setEmailVerifiedAt(verified ? NOW.minusSeconds(60) : null);
        user = userAccountRepository.saveAndFlush(user);
        localCredentialRepository.saveAndFlush(new LocalCredentialEntity(user, passwordEncoder.encode(password)));
        if (student) {
            userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        }
        return user;
    }

    private void assertNoCredentialResponse(ResponseEntity<String> response, String secret) {
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertNull(response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertFalse(response.getBody() != null && response.getBody().contains(secret));
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private String username(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@gmail.com";
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private String text(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private Object value(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Object.class, arguments);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestResourceServerConfiguration {
        @Bean
        JwtDecoder testJwtDecoder(@Qualifier("identitySigningJwk") RSAKey signingJwk) throws JOSEException {
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withPublicKey(signingJwk.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
