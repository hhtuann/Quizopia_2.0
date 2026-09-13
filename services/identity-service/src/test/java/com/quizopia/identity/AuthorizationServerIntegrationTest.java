package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.serviceclient.ServiceClientDescriptor;
import com.quizopia.identity.application.serviceclient.ServiceClientRegistration;
import com.quizopia.identity.application.serviceclient.ServiceClientRegistrationService;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.token.IssuedUserAccessToken;
import com.quizopia.identity.security.token.QuizopiaTokenClaims;
import com.quizopia.identity.security.token.UserAccessTokenIssuanceException;
import com.quizopia.identity.security.token.UserAccessTokenIssuer;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("persistence-test")
@Import(AuthorizationServerIntegrationTest.UpgradePasswordEncoderConfiguration.class)
class AuthorizationServerIntegrationTest {
    private static final String ISSUER = "https://identity.test";
    private static final String KEY_ID = "wave-1a-step-6-test-key";
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofSeconds(45);
    private static final Duration USER_ACCESS_TOKEN_TTL = Duration.ofMinutes(5);
    private static final Instant USER_TOKEN_ISSUED_AT = Instant.parse("2026-09-13T00:00:00Z");
    private static final TestKeyMaterial KEY_MATERIAL = TestKeyMaterial.create();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceClientRegistrationService registrationService;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserAccessTokenIssuer userAccessTokenIssuer;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    void validClientCredentialsProduceVerifiableRs256JwtWithoutRefreshToken() throws Exception {
        ServiceClientRegistration registration = registerClient(Set.of("test.read", "test.write"));

        ResponseEntity<String> response = requestToken(registration, "client_credentials", "test.read");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("Bearer", body.path("token_type").asText());
        assertEquals("test.read", body.path("scope").asText());
        assertFalse(body.has("refresh_token"));

        String accessToken = body.path("access_token").asText();
        SignedJWT signedJwt = SignedJWT.parse(accessToken);
        assertEquals(JWSAlgorithm.RS256, signedJwt.getHeader().getAlgorithm());
        assertEquals(KEY_ID, signedJwt.getHeader().getKeyID());
        assertEquals(ISSUER, signedJwt.getJWTClaimsSet().getIssuer());
        assertEquals(registration.clientId(), signedJwt.getJWTClaimsSet().getSubject());
        assertEquals(
                QuizopiaTokenClaims.SERVICE,
                signedJwt.getJWTClaimsSet().getStringClaim(QuizopiaTokenClaims.PRINCIPAL_TYPE));
        assertNull(signedJwt.getJWTClaimsSet().getClaim(QuizopiaTokenClaims.ROLES));
        assertEquals(
                ACCESS_TOKEN_TTL.toSeconds(),
                Duration.between(
                                signedJwt.getJWTClaimsSet().getIssueTime().toInstant(),
                                signedJwt.getJWTClaimsSet().getExpirationTime().toInstant())
                        .toSeconds());
        Object rawScopeClaim = signedJwt.getJWTClaimsSet().getClaim(QuizopiaTokenClaims.SCOPE);
        assertInstanceOf(List.class, rawScopeClaim);
        List<?> decodedScopes = (List<?>) rawScopeClaim;
        assertFalse(decodedScopes.isEmpty());
        assertTrue(decodedScopes.stream().allMatch(String.class::isInstance));
        assertEquals(List.of("test.read"), decodedScopes);

        JWKSet publicJwkSet = fetchPublicJwkSet();
        RSAKey publicKey = (RSAKey) publicJwkSet.getKeyByKeyId(KEY_ID);
        assertNotNull(publicKey);
        JWSVerifier correspondingVerifier = new RSASSAVerifier(publicKey.toRSAPublicKey());
        assertTrue(signedJwt.verify(correspondingVerifier));

        KeyPair unrelatedKeyPair = generateRsaKeyPair();
        assertFalse(signedJwt.verify(
                new RSASSAVerifier((java.security.interfaces.RSAPublicKey) unrelatedKeyPair.getPublic())));
    }

    @Test
    void activeStudentReceivesSignedUserTokenWithExactPrincipalContract() throws Exception {
        UserAccountEntity account = activeVerifiedUser(Set.of(UserRole.STUDENT));

        IssuedUserAccessToken issued = userAccessTokenIssuer.issue(account.getId());
        SignedJWT signedJwt = SignedJWT.parse(issued.value());

        assertEquals(JWSAlgorithm.RS256, signedJwt.getHeader().getAlgorithm());
        assertEquals(KEY_ID, signedJwt.getHeader().getKeyID());
        assertEquals(ISSUER, signedJwt.getJWTClaimsSet().getIssuer());
        assertEquals(account.getId().toString(), signedJwt.getJWTClaimsSet().getSubject());
        assertEquals(
                QuizopiaTokenClaims.USER,
                signedJwt.getJWTClaimsSet().getStringClaim(QuizopiaTokenClaims.PRINCIPAL_TYPE));
        assertEquals(
                Set.of("STUDENT"),
                new HashSet<>(signedJwt.getJWTClaimsSet().getStringListClaim(QuizopiaTokenClaims.ROLES)));
        assertNull(signedJwt.getJWTClaimsSet().getClaim(QuizopiaTokenClaims.SCOPE));
        assertEquals(
                USER_TOKEN_ISSUED_AT, signedJwt.getJWTClaimsSet().getIssueTime().toInstant());
        assertEquals(
                USER_TOKEN_ISSUED_AT.plus(USER_ACCESS_TOKEN_TTL),
                signedJwt.getJWTClaimsSet().getExpirationTime().toInstant());
        assertFalse(issued.toString().contains(issued.value()));
        assertSignedByConfiguredKey(signedJwt);
    }

    @Test
    void enabledTeacherReceivesStudentAndTeacherRolesWithoutServiceScopes() throws Exception {
        UserAccountEntity account = activeVerifiedUser(Set.of(UserRole.STUDENT, UserRole.TEACHER));

        SignedJWT signedJwt =
                SignedJWT.parse(userAccessTokenIssuer.issue(account.getId()).value());

        assertEquals(
                Set.of("STUDENT", "TEACHER"),
                new HashSet<>(signedJwt.getJWTClaimsSet().getStringListClaim(QuizopiaTokenClaims.ROLES)));
        assertEquals(
                QuizopiaTokenClaims.USER,
                signedJwt.getJWTClaimsSet().getStringClaim(QuizopiaTokenClaims.PRINCIPAL_TYPE));
        assertNull(signedJwt.getJWTClaimsSet().getClaim(QuizopiaTokenClaims.SCOPE));
        assertSignedByConfiguredKey(signedJwt);
    }

    @Test
    void userTokenIssuanceRejectsUnverifiedOrInvariantBreakingAccounts() {
        UserAccountEntity pending =
                new UserAccountEntity("pending-" + UUID.randomUUID() + "@example.com", "pending-" + UUID.randomUUID());
        pending.setAccountStatus(AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION);
        userAccountRepository.saveAndFlush(pending);

        UserAccountEntity activeWithoutStudent =
                new UserAccountEntity("active-" + UUID.randomUUID() + "@example.com", "active-" + UUID.randomUUID());
        activeWithoutStudent.setAccountStatus(AccountLifecycleStatus.ACTIVE);
        activeWithoutStudent.setEmailVerifiedAt(USER_TOKEN_ISSUED_AT.minusSeconds(60));
        userAccountRepository.saveAndFlush(activeWithoutStudent);

        assertThrows(UserAccessTokenIssuanceException.class, () -> userAccessTokenIssuer.issue(pending.getId()));
        assertThrows(
                UserAccessTokenIssuanceException.class,
                () -> userAccessTokenIssuer.issue(activeWithoutStudent.getId()));
        assertThrows(UserAccessTokenIssuanceException.class, () -> userAccessTokenIssuer.issue(UUID.randomUUID()));
    }

    @Test
    void wrongSecretAndUnknownClientReturnGenericInvalidClient() {
        ServiceClientRegistration registration = registerClient(Set.of("test.read"));

        ResponseEntity<String> wrongSecret =
                requestTokenWithCredentials(registration.clientId(), "wrong-secret", "client_credentials", null);
        ResponseEntity<String> unknownClient = requestTokenWithCredentials(
                "unknown-client-" + UUID.randomUUID(), "unknown-secret", "client_credentials", null);

        assertInvalidClient(wrongSecret);
        assertInvalidClient(unknownClient);
    }

    @Test
    void disabledClientCannotAuthenticateAndRemainsDisabled() {
        ServiceClientRegistration registration = registerClient(Set.of("test.read"));
        registrationService.setEnabled(registration.clientId(), false);

        ResponseEntity<String> response = requestToken(registration, "client_credentials", null);
        ServiceClientDescriptor persisted =
                registrationService.findByClientId(registration.clientId()).orElseThrow();

        assertInvalidClient(response);
        assertFalse(persisted.enabled());
    }

    @Test
    @Transactional
    void findByIdReturnsDetachedMappedScopesAndHidesDisabledClient() {
        Set<String> persistedScopes = Set.of("test.read", "test.write");
        ServiceClientRegistration registration = registerClient(persistedScopes);

        entityManager.flush();
        entityManager.clear();

        RegisteredClient registeredClient = registeredClientRepository.findById(
                registration.serviceClientId().toString());

        entityManager.clear();
        assertNotNull(registeredClient);
        assertEquals(persistedScopes, registeredClient.getScopes());

        registrationService.setEnabled(registration.clientId(), false);
        entityManager.flush();
        entityManager.clear();

        assertNull(registeredClientRepository.findById(
                registration.serviceClientId().toString()));
    }

    @Test
    void clientSecretAuthenticationUpgradesLegacyEncodingAndPreservesRegistration() {
        ServiceClientRegistration registration = registerClient(Set.of("test.read", "test.write"));
        String rawSecret = registration.clientSecret().value();
        String legacyHash = controlledLegacyHash(rawSecret);
        jdbcTemplate.update(
                "UPDATE oauth2_service_client SET client_secret_hash = ? WHERE id = ?",
                legacyHash,
                registration.serviceClientId());
        ServiceClientDescriptor before =
                registrationService.findByClientId(registration.clientId()).orElseThrow();
        long clientRows = count("oauth2_service_client");

        ResponseEntity<String> response = requestToken(registration, "client_credentials", "test.read");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(
                objectMapper.readTree(response.getBody()).path("access_token").asText(null));
        String upgradedHash = jdbcTemplate.queryForObject(
                "SELECT client_secret_hash FROM oauth2_service_client WHERE id = ?",
                String.class,
                registration.serviceClientId());
        ServiceClientDescriptor after =
                registrationService.findByClientId(registration.clientId()).orElseThrow();
        assertNotEquals(legacyHash, upgradedHash);
        assertTrue(upgradedHash.startsWith("{current}"));
        assertFalse(upgradedHash.contains(rawSecret));
        assertEquals(clientRows, count("oauth2_service_client"));
        assertEquals(before.serviceClientId(), after.serviceClientId());
        assertEquals(before.clientId(), after.clientId());
        assertEquals(before.scopes(), after.scopes());
        assertEquals(before.enabled(), after.enabled());
        assertEquals(before.grantType(), after.grantType());
        assertEquals(before.authenticationMethod(), after.authenticationMethod());

        ResponseEntity<String> subsequentResponse = requestToken(registration, "client_credentials", "test.read");
        assertEquals(HttpStatus.OK, subsequentResponse.getStatusCode());
    }

    @Test
    void unsupportedGrantAndUnauthorizedScopeAreRejected() {
        ServiceClientRegistration registration = registerClient(Set.of("test.read"));

        ResponseEntity<String> unsupportedGrant = requestToken(registration, "authorization_code", null);
        ResponseEntity<String> unauthorizedScope = requestToken(registration, "client_credentials", "test.write");

        assertTrue(unsupportedGrant.getStatusCode() == HttpStatus.BAD_REQUEST
                || unsupportedGrant.getStatusCode() == HttpStatus.UNAUTHORIZED);
        assertTrue(
                unsupportedGrant.getBody() != null && unsupportedGrant.getBody().contains("\"error\""),
                unsupportedGrant.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, unauthorizedScope.getStatusCode());
        assertTrue(unauthorizedScope.getBody().contains("invalid_scope"));
    }

    @Test
    void nonStepSixAuthorizationEndpointsAreNotPublicSasSurface() {
        assertNotPublicSasEndpoint(restTemplate.getForEntity(url("/oauth2/authorize"), String.class));
        assertNotPublicSasEndpoint(unauthenticatedPost("/oauth2/introspect"));
        assertNotPublicSasEndpoint(unauthenticatedPost("/oauth2/revoke"));
        assertNotPublicSasEndpoint(
                restTemplate.getForEntity(url("/.well-known/oauth-authorization-server"), String.class));
        assertNotPublicSasEndpoint(restTemplate.getForEntity(url("/.well-known/openid-configuration"), String.class));
    }

    @Test
    void jwksIsPublicAndContainsOnlyConfiguredPublicRsaParameters() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/oauth2/jwks"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
        JWKSet jwkSet = JWKSet.parse(response.getBody());
        JWK key = jwkSet.getKeyByKeyId(KEY_ID);
        assertTrue(key instanceof RSAKey);
        Map<String, Object> json = key.toJSONObject();
        assertEquals(KEY_ID, json.get("kid"));
        assertEquals("RSA", json.get("kty"));
        assertTrue(json.containsKey("n"));
        assertTrue(json.containsKey("e"));
        for (String privateParameter : List.of("d", "p", "q", "dp", "dq", "qi", "oth")) {
            assertFalse(json.containsKey(privateParameter), privateParameter);
        }
        assertFalse(response.getBody().contains("BEGIN PRIVATE KEY"));
        assertFalse(response.getBody().contains("BEGIN RSA PRIVATE KEY"));
    }

    @Test
    void clientCredentialsDoesNotCreateUsersOrRefreshSessionRows() {
        ServiceClientRegistration registration = registerClient(Set.of("test.read"));
        long usersBefore = count("user_account");
        long familiesBefore = count("refresh_token_family");
        long tokensBefore = count("refresh_token");

        ResponseEntity<String> response = requestToken(registration, "client_credentials", "test.read");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(objectMapper.readTree(response.getBody()).has("refresh_token"));
        assertEquals(usersBefore, count("user_account"));
        assertEquals(familiesBefore, count("refresh_token_family"));
        assertEquals(tokensBefore, count("refresh_token"));
    }

    private UserAccountEntity activeVerifiedUser(Set<UserRole> roles) {
        UserAccountEntity account =
                new UserAccountEntity("token-" + UUID.randomUUID() + "@example.com", "token-" + UUID.randomUUID());
        account.setAccountStatus(AccountLifecycleStatus.ACTIVE);
        account.setEmailVerifiedAt(USER_TOKEN_ISSUED_AT.minusSeconds(60));
        userAccountRepository.saveAndFlush(account);
        roles.forEach(role -> userRoleRepository.saveAndFlush(new UserRoleEntity(account, role)));
        return account;
    }

    private void assertSignedByConfiguredKey(SignedJWT signedJwt) throws Exception {
        RSAKey publicKey = (RSAKey) fetchPublicJwkSet().getKeyByKeyId(KEY_ID);
        assertNotNull(publicKey);
        assertTrue(signedJwt.verify(new RSASSAVerifier(publicKey.toRSAPublicKey())));
    }

    private ServiceClientRegistration registerClient(Set<String> scopes) {
        return registrationService.register("test-step-6-client-" + UUID.randomUUID(), scopes);
    }

    private ResponseEntity<String> requestToken(
            ServiceClientRegistration registration, String grantType, String scope) {
        return requestTokenWithCredentials(
                registration.clientId(), registration.clientSecret().value(), grantType, scope);
    }

    private ResponseEntity<String> requestTokenWithCredentials(
            String clientId, String clientSecret, String grantType, String scope) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        if (scope != null) {
            form.add("scope", scope);
        }
        return restTemplate.exchange(
                url("/oauth2/token"), HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    private JWKSet fetchPublicJwkSet() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/oauth2/jwks"), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return JWKSet.parse(response.getBody());
    }

    private void assertInvalidClient(ResponseEntity<String> response) {
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("invalid_client"));
    }

    private ResponseEntity<String> unauthenticatedPost(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.exchange(
                url(path), HttpMethod.POST, new HttpEntity<>(new LinkedMultiValueMap<>(), headers), String.class);
    }

    private void assertNotPublicSasEndpoint(ResponseEntity<String> response) {
        assertNotEquals(HttpStatus.OK, response.getStatusCode());
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static String controlledLegacyHash(String rawSecret) {
        return "{old}" + digest(rawSecret);
    }

    private static String digest(String rawSecret) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(rawSecret.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("quizopia.identity.security.authorization-server.enabled", () -> true);
        registry.add("quizopia.identity.security.authorization-server.issuer", () -> ISSUER);
        registry.add(
                "quizopia.identity.security.authorization-server.service-access-token-ttl",
                () -> ACCESS_TOKEN_TTL.toString());
        registry.add(
                "quizopia.identity.security.authorization-server.user-access-token-ttl",
                () -> USER_ACCESS_TOKEN_TTL.toString());
        registry.add("quizopia.identity.security.signing-key.kid", () -> KEY_ID);
        registry.add(
                "quizopia.identity.security.signing-key.private-key-path",
                () -> KEY_MATERIAL.privateKeyPath().toString());
        registry.add(
                "quizopia.identity.security.signing-key.public-key-path",
                () -> KEY_MATERIAL.publicKeyPath().toString());
    }

    @AfterAll
    static void cleanUpKeyMaterial() throws IOException {
        try (Stream<Path> paths = Files.walk(KEY_MATERIAL.directory())) {
            paths.sorted((first, second) -> second.compareTo(first)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Test key material could not be removed", exception);
                }
            });
        }
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UpgradePasswordEncoderConfiguration {
        @Bean
        @Primary
        PasswordEncoder authorizationServerPasswordEncoder() {
            return new ControlledUpgradePasswordEncoder();
        }

        @Bean
        @Primary
        Clock authorizationServerClock() {
            return Clock.fixed(USER_TOKEN_ISSUED_AT, java.time.ZoneOffset.UTC);
        }
    }

    private static final class ControlledUpgradePasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegatingPasswordEncoder =
                PasswordEncoderFactories.createDelegatingPasswordEncoder();

        @Override
        public String encode(CharSequence rawPassword) {
            return "{current}" + digest(rawPassword.toString());
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            String digest = digest(rawPassword.toString());
            return encodedPassword.equals("{old}" + digest)
                    || encodedPassword.equals("{current}" + digest)
                    || delegatingPasswordEncoder.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            if (encodedPassword == null || encodedPassword.startsWith("{old}")) {
                return true;
            }
            if (encodedPassword.startsWith("{current}")) {
                return false;
            }
            return delegatingPasswordEncoder.upgradeEncoding(encodedPassword);
        }
    }

    private record TestKeyMaterial(Path directory, Path privateKeyPath, Path publicKeyPath) {
        private static TestKeyMaterial create() {
            try {
                Path directory = Files.createTempDirectory("quizopia-step-6-");
                KeyPair keyPair = generateRsaKeyPair();
                Path privateKeyPath = directory.resolve("private-key.pem");
                Path publicKeyPath = directory.resolve("public-key.pem");
                Files.writeString(
                        privateKeyPath, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
                Files.writeString(
                        publicKeyPath, pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
                return new TestKeyMaterial(directory, privateKeyPath, publicKeyPath);
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private static String pem(String type, byte[] encoded) {
            return "-----BEGIN "
                    + type
                    + "-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(encoded)
                    + "\n-----END "
                    + type
                    + "-----\n";
        }
    }
}
