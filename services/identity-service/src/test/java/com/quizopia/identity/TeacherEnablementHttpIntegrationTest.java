package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.identity.api.auth.AuthController;
import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.token.QuizopiaTokenClaims;
import com.quizopia.identity.support.TestRsaKeyMaterial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("persistence-test")
@Import(TeacherEnablementHttpIntegrationTest.TestResourceServerConfiguration.class)
class TeacherEnablementHttpIntegrationTest {
    private static final String ISSUER = "https://identity.teacher-enablement.test";
    private static final String KEY_ID = "teacher-enablement-test-key";
    private static final String TRUSTED_ORIGIN = "http://localhost:3000";
    private static final Duration USER_ACCESS_TOKEN_TTL = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final TestRsaKeyMaterial KEY_MATERIAL = TestRsaKeyMaterial.create("quizopia-teacher-");

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
        registry.add("quizopia.identity.security.authorization-server.user-access-token-ttl", () -> "PT5M");
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
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private LocalCredentialRepository localCredentialRepository;

    @Autowired
    @Qualifier("serviceClientPasswordEncoder") private PasswordEncoder passwordEncoder;

    @MockitoBean(name = "identityClock")
    private Clock clock;

    @AfterAll
    static void removeTestKeys() throws Exception {
        KEY_MATERIAL.close();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM role_grant_audit");
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM refresh_token_family");
        jdbc.update("DELETE FROM local_credential");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM user_access_revocation");
        jdbc.update("DELETE FROM user_account");
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void oldTokenGrantRefreshAndMeUseAuthoritativeCurrentRolesWithoutReplacingFamily() throws Exception {
        UserAccountEntity user = persistUser(AccountLifecycleStatus.ACTIVE, true, true);
        ResponseEntity<String> login = login(user.getUsername(), "teacher-secret");
        assertEquals(HttpStatus.OK, login.getStatusCode());
        String oldAccessToken = json(login).path("accessToken").asText();
        String refreshCredential = refreshCookieValue(login);
        UUID familyId = jdbc.queryForObject("SELECT id FROM refresh_token_family", UUID.class);
        Instant familyExpiresAt = jdbc.queryForObject("SELECT expires_at FROM refresh_token_family", Instant.class);
        assertEquals(List.of("STUDENT"), tokenRoles(oldAccessToken));

        ResponseEntity<String> granted = enableTeacher(oldAccessToken);

        assertEquals(HttpStatus.NO_CONTENT, granted.getStatusCode());
        assertNull(granted.getBody());
        assertEquals(List.of("STUDENT"), tokenRoles(oldAccessToken));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));

        Instant refreshTime = NOW.plusSeconds(60);
        when(clock.instant()).thenReturn(refreshTime);
        ResponseEntity<String> refreshed = refresh(refreshCredential);
        assertEquals(HttpStatus.OK, refreshed.getStatusCode());
        String freshAccessToken = json(refreshed).path("accessToken").asText();

        assertEquals(List.of("STUDENT", "TEACHER"), tokenRoles(freshAccessToken));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(familyId, jdbc.queryForObject("SELECT id FROM refresh_token_family", UUID.class));
        assertEquals(
                familyExpiresAt, jdbc.queryForObject("SELECT expires_at FROM refresh_token_family", Instant.class));
        assertEquals(2L, count("SELECT COUNT(*) FROM refresh_token"));
        assertEquals(1L, count("SELECT COUNT(*) FROM role_grant_audit"));

        ResponseEntity<String> me = me(freshAccessToken);
        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertEquals(List.of("STUDENT", "TEACHER"), objectMapper.convertValue(json(me).path("roles"), List.class));
    }

    @Test
    void firstGrantAndAlreadyEnabledReturnTheSameEmptyNoContentResponse() {
        UserAccountEntity user = persistUser(AccountLifecycleStatus.ACTIVE, true, true);
        String token = signedUserToken(user.getId(), List.of("STUDENT"), Map.of());

        ResponseEntity<String> first = enableTeacher(token);
        ResponseEntity<String> repeated = enableTeacher(token);

        assertEquals(HttpStatus.NO_CONTENT, first.getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, repeated.getStatusCode());
        assertNull(first.getBody());
        assertNull(repeated.getBody());
        assertEquals(1L, count("SELECT COUNT(*) FROM user_role WHERE role = 'TEACHER'"));
        assertEquals(1L, count("SELECT COUNT(*) FROM role_grant_audit"));
    }

    @Test
    void endpointRejectsMissingInvalidServiceAndMalformedPrincipalClaims() {
        assertEquals(HttpStatus.UNAUTHORIZED, enableTeacher(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, enableTeacher("not-a-jwt").getStatusCode());
        assertAccessStatus(HttpStatus.FORBIDDEN, enableTeacher(signedServiceToken()));

        String mixed = signedUserToken(
                UUID.randomUUID(),
                List.of("STUDENT"),
                Map.of(QuizopiaTokenClaims.SCOPE, List.of("classroom.membership.read")));
        assertEquals(HttpStatus.UNAUTHORIZED, enableTeacher(mixed).getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                enableTeacher(signedToken(
                                "not-a-uuid",
                                QuizopiaTokenClaims.USER,
                                Map.of(QuizopiaTokenClaims.ROLES, List.of("STUDENT"))))
                        .getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                enableTeacher(signedToken(
                                UUID.randomUUID().toString(),
                                "UNKNOWN",
                                Map.of(QuizopiaTokenClaims.ROLES, List.of("STUDENT"))))
                        .getStatusCode());
    }

    @Test
    void ineligibleAndUnknownUsersReceiveOneGenericForbiddenEnvelope() throws Exception {
        UserAccountEntity pending = persistUser(AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION, false, true);
        ResponseEntity<String> pendingResponse =
                enableTeacher(signedUserToken(pending.getId(), List.of("STUDENT"), Map.of()));
        ResponseEntity<String> unknownResponse =
                enableTeacher(signedUserToken(UUID.randomUUID(), List.of("STUDENT"), Map.of()));

        for (ResponseEntity<String> response : List.of(pendingResponse, unknownResponse)) {
            assertAccessStatus(HttpStatus.FORBIDDEN, response);
            JsonNode body = json(response);
            assertEquals("ACCESS_DENIED", body.path("code").asText());
            assertFalse(response.getBody().contains("VERIFIED"));
            assertFalse(response.getBody().contains("PENDING"));
            assertFalse(response.getBody().contains("NOT_FOUND"));
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM role_grant_audit"));
    }

    @Test
    void openApiPublishesOnlyTheAcceptedPostContract() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode path = json(response).path("paths").path(AuthController.TEACHER_ENABLEMENT_PATH);
        assertTrue(path.has("post"));
        assertFalse(path.has("get"));
        assertTrue(path.path("post").path("responses").has("204"));
        assertTrue(path.path("post").path("responses").has("401"));
        assertTrue(path.path("post").path("responses").has("403"));
    }

    private UserAccountEntity persistUser(String status, boolean verified, boolean student) {
        String suffix = UUID.randomUUID().toString();
        UserAccountEntity user = new UserAccountEntity("http-" + suffix + "@gmail.com", "http-" + suffix);
        user.setAccountStatus(status);
        user.setEmailVerifiedAt(verified ? NOW.minusSeconds(60) : null);
        user = userAccountRepository.saveAndFlush(user);
        localCredentialRepository.saveAndFlush(
                new LocalCredentialEntity(user, passwordEncoder.encode("teacher-secret")));
        if (student) {
            userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        }
        return user;
    }

    private ResponseEntity<String> login(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                AuthController.LOGIN_PATH,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("identifier", username, "password", password), headers),
                String.class);
    }

    private ResponseEntity<String> enableTeacher(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return restTemplate.exchange(
                AuthController.TEACHER_ENABLEMENT_PATH, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> refresh(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, TRUSTED_ORIGIN);
        headers.add(HttpHeaders.COOKIE, "quizopia_refresh=" + credential);
        return restTemplate.exchange(
                AuthController.REFRESH_PATH, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> me(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange(AuthController.ME_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String signedUserToken(UUID userId, List<String> roles, Map<String, Object> extraClaims) {
        java.util.LinkedHashMap<String, Object> claims = new java.util.LinkedHashMap<>(extraClaims);
        claims.put(QuizopiaTokenClaims.ROLES, roles);
        return signedToken(userId.toString(), QuizopiaTokenClaims.USER, claims);
    }

    private String signedServiceToken() {
        return signedToken(
                "assessment-service",
                QuizopiaTokenClaims.SERVICE,
                Map.of(QuizopiaTokenClaims.SCOPE, List.of("classroom.membership.read")));
    }

    private String signedToken(String subject, String principalType, Map<String, Object> additionalClaims) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(NOW)
                .expiresAt(NOW.plus(USER_ACCESS_TOKEN_TTL))
                .claim(QuizopiaTokenClaims.PRINCIPAL_TYPE, principalType);
        additionalClaims.forEach(claims::claim);
        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims.build()))
                .getTokenValue();
    }

    private List<String> tokenRoles(String token) throws Exception {
        return SignedJWT.parse(token).getJWTClaimsSet().getStringListClaim(QuizopiaTokenClaims.ROLES);
    }

    private String refreshCookieValue(ResponseEntity<String> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        return setCookie.substring("quizopia_refresh=".length(), setCookie.indexOf(';'));
    }

    private void assertAccessStatus(HttpStatus expected, ResponseEntity<String> response) {
        assertEquals(expected, response.getStatusCode());
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
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
