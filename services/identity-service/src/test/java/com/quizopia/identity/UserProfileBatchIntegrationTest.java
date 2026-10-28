package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.identity.application.serviceclient.ServiceClientRegistration;
import com.quizopia.identity.application.serviceclient.ServiceClientRegistrationService;
import com.quizopia.identity.configuration.UserAdmissionConfiguration;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.token.QuizopiaTokenClaims;
import com.quizopia.identity.support.TestRsaKeyMaterial;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("persistence-test")
class UserProfileBatchIntegrationTest {
    static final String ISSUER = "https://identity.profiles.test";
    static final String BATCH_PATH = "/internal/security/user-profiles/batch";
    static final TestRsaKeyMaterial KEYS = TestRsaKeyMaterial.create("profiles-test-");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "1s");
        registry.add("spring.data.redis.connect-timeout", () -> "1s");
        registry.add("quizopia.identity.security.authorization-server.enabled", () -> true);
        registry.add("quizopia.identity.security.authorization-server.issuer", () -> ISSUER);
        registry.add("quizopia.identity.security.signing-key.kid", () -> "profiles-test");
        registry.add("quizopia.identity.security.signing-key.private-key-path", () -> KEYS.privateKeyPath()
                .toString());
        registry.add("quizopia.identity.security.signing-key.public-key-path", () -> KEYS.publicKeyPath()
                .toString());
        registry.add("quizopia.identity.user-profiles.enabled", () -> true);
        registry.add("quizopia.identity.user-profiles.require-secure-transport", () -> false);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    UserRoleRepository roles;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    ServiceClientRegistrationService clients;

    @Autowired
    jakarta.persistence.EntityManagerFactory entityManagerFactory;

    ServiceClientRegistration allowed;
    ServiceClientRegistration wrongClient;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE oauth2_service_client CASCADE");
        allowed = clients.register("assessment-service", Set.of(UserAdmissionConfiguration.PROFILE_SCOPE));
        wrongClient = clients.register("quiz-service", Set.of(UserAdmissionConfiguration.PROFILE_SCOPE));
        jdbc.execute("TRUNCATE user_account CASCADE");
    }

    @AfterAll
    static void cleanup() throws Exception {
        KEYS.close();
    }

    @Test
    void fullBatchUsesOneQueryWithoutNPlusOne() {
        var first = student("query-one-" + UUID.randomUUID(), "One");
        var second = student("query-two-" + UUID.randomUUID(), "Two");
        var ids = new ArrayList<UUID>(List.of(first.getId(), second.getId()));
        while (ids.size() < 100) ids.add(UUID.randomUUID());
        String token = service(allowed);
        var statistics =
                entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertEquals(200, batch(token, body(ids), Map.of()).getStatusCode().value());
            assertEquals(1, statistics.getPrepareStatementCount(), "No per-id queries or lazy profile loading");
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    @org.springframework.test.annotation.DirtiesContext
    void rollingBudgetCannotBeBypassedByTokenRotationOrChangingRequestedIds() {
        String firstToken = service(allowed);
        String rotatedToken = service(allowed);
        boolean limited = false;
        for (int i = 0; i <= 60; i++) {
            var response = batch(i % 2 == 0 ? firstToken : rotatedToken, body(List.of(UUID.randomUUID())), Map.of());
            if (response.getStatusCode().value() == 429) {
                assertError(response, 429, "PROFILE_RATE_LIMITED");
                limited = true;
                break;
            }
            assertEquals(200, response.getStatusCode().value(), response.getBody());
        }
        assertTrue(limited, "Sequential requests must exhaust the rolling-minute budget");
        var statistics =
                entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertError(batch(rotatedToken, "not-json", Map.of()), 429, "PROFILE_RATE_LIMITED");
            assertEquals(0, statistics.getPrepareStatementCount(), "Rate rejection must precede database lookup");
        } finally {
            statistics.setStatisticsEnabled(false);
        }
        assertError(
                batch(service(wrongClient), body(List.of(UUID.randomUUID())), Map.of()),
                403,
                "SERVICE_CALLER_FORBIDDEN");
        assertError(batch(null, body(List.of(UUID.randomUUID())), Map.of()), 401, "SERVICE_AUTHENTICATION_INVALID");
    }

    @Test
    void unicodeDisplayNameAndFullLengthLegacyUsernameRoundTripThroughPersistence() throws Exception {
        String unicode = "\ud83d\ude00".repeat(100);
        String username = "u".repeat(255);
        var named = student("unicode-" + UUID.randomUUID(), unicode);
        var legacy = student(username, null);
        var response = batch(service(allowed), body(List.of(named.getId(), legacy.getId())), Map.of());
        assertEquals(200, response.getStatusCode().value());
        var names = new HashSet<String>();
        mapper.readTree(response.getBody())
                .path("items")
                .forEach(item -> names.add(item.path("displayName").asString()));
        assertEquals(Set.of(unicode, username), names);
    }

    @Test
    void authorizedAssessmentCallerReceivesSortedProfilesWithUsernameFallback() throws Exception {
        UserAccountEntity named = student("named-" + UUID.randomUUID(), "Nguyễn Văn An");
        UserAccountEntity legacy = student("legacy-" + UUID.randomUUID(), null);
        UUID unknown = UUID.randomUUID();
        List<UUID> expectedOrder = new ArrayList<>(List.of(named.getId(), legacy.getId()));
        expectedOrder.sort(Comparator.naturalOrder());

        ResponseEntity<String> response =
                batch(service(allowed), body(List.of(legacy.getId(), unknown, named.getId())), Map.of());

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var root = mapper.readTree(response.getBody());
        assertEquals(1, root.size());
        var items = root.path("items");
        assertEquals(2, items.size());
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            assertEquals(2, item.size(), item.toString());
            assertEquals(
                    expectedOrder.get(index).toString(), item.path("studentId").asString());
            if (item.path("studentId").asString().equals(named.getId().toString())) {
                assertEquals("Nguyễn Văn An", item.path("displayName").asString());
            } else {
                assertEquals(legacy.getUsername(), item.path("displayName").asString());
            }
            assertFalse(item.has("email"));
            assertFalse(item.has("roles"));
            assertFalse(item.has("status"));
        }
        assertFalse(response.getBody().contains(named.getEmail()));
        assertFalse(response.getBody().contains(legacy.getEmail()));
        assertFalse(response.getBody().contains("@gmail.com"));
        assertFalse(response.getBody().contains("STUDENT"));
    }

    @Test
    void disabledDeletedAndUnknownAccountsAreOmittedEntirely() throws Exception {
        UserAccountEntity named = student("named-" + UUID.randomUUID(), "Visible Name");
        UserAccountEntity disabled = student("disabled-" + UUID.randomUUID(), "Hidden Name");
        jdbc.update("UPDATE user_account SET account_status = 'DISABLED' WHERE id = ?", disabled.getId());
        UserAccountEntity deleted = student("deleted-" + UUID.randomUUID(), "Gone Name");
        jdbc.update("DELETE FROM user_account WHERE id = ?", deleted.getId());

        ResponseEntity<String> response =
                batch(service(allowed), body(List.of(named.getId(), disabled.getId(), deleted.getId())), Map.of());

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        var items = mapper.readTree(response.getBody()).path("items");
        assertEquals(1, items.size());
        assertEquals(named.getId().toString(), items.get(0).path("studentId").asString());
        assertFalse(response.getBody().contains("Hidden Name"));
        assertFalse(response.getBody().contains("Gone Name"));
    }

    @Test
    void wrongClientMissingScopeAndUserPrincipalsAreForbidden() {
        UserAccountEntity student = student("authz-" + UUID.randomUUID(), null);
        String valid = body(List.of(student.getId()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String missingScope = signed(
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of("identity.notice-recipient.read")),
                ISSUER,
                now.minusSeconds(1),
                now.plusSeconds(120));
        String userToken = signed(
                student.getId().toString(),
                "USER",
                Map.of("roles", List.of("STUDENT")),
                ISSUER,
                now.minusSeconds(1),
                now.plusSeconds(120));
        for (String caller : List.of(service(wrongClient), missingScope, userToken))
            assertError(batch(caller, valid, Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(batch(null, valid, Map.of()), 401, "SERVICE_AUTHENTICATION_INVALID");
        assertError(batch(corrupt(service(allowed)), valid, Map.of()), 401, "SERVICE_AUTHENTICATION_INVALID");
        String wrongScopeFamily = signed(
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of(UserAdmissionConfiguration.SCOPE)),
                ISSUER,
                now.minusSeconds(1),
                now.plusSeconds(120));
        assertError(batch(wrongScopeFamily, valid, Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
    }

    @Test
    void expiredServiceCredentialIsRejected() {
        UserAccountEntity student = student("expired-" + UUID.randomUUID(), null);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String expired = signed(
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of(UserAdmissionConfiguration.PROFILE_SCOPE)),
                ISSUER,
                now.minusSeconds(120),
                now.minusSeconds(1));
        assertError(batch(expired, body(List.of(student.getId())), Map.of()), 401, "SERVICE_AUTHENTICATION_INVALID");
    }

    @Test
    void batchInputShapesAreStrictlyBoundedAndCanonical() {
        UserAccountEntity student = student("strict-" + UUID.randomUUID(), null);
        String token = service(allowed);
        String canonical = student.getId().toString();
        String upper = canonical.toUpperCase(Locale.ROOT);
        String undashed = canonical.replace("-", "");
        List<UUID> overfull =
                IntStream.range(0, 101).mapToObj(ignored -> UUID.randomUUID()).collect(Collectors.toList());
        List<UUID> maximal =
                IntStream.range(0, 100).mapToObj(ignored -> UUID.randomUUID()).collect(Collectors.toList());
        for (String payload : List.of(
                body(overfull),
                "{\"studentIds\":[\"" + canonical + "\",\"" + canonical + "\"]}",
                "{\"studentIds\":[\"" + upper + "\"]}",
                "{\"studentIds\":[\"" + undashed + "\"]}",
                "{\"studentIds\":[\"not-a-uuid\"]}",
                "{\"studentIds\":[]}",
                "{\"studentIds\":\"" + canonical + "\"}",
                "{\"studentIds\":[123]}",
                "{\"studentIds\":[null]}",
                "{\"studentIds\":[true]}",
                "{}",
                "{\"other\":[]}",
                "{\"studentIds\":[\"" + canonical + "\"],\"extra\":1}",
                "{\"studentIds\":[\"" + canonical + "\"],\"studentIds\":[\"" + canonical + "\"]}",
                body(List.of(student.getId())) + " {}",
                "",
                "null",
                "{\"studentIds\":[\"" + canonical + "\"],\"pad\":\"" + "A".repeat(17000) + "\"}")) {
            var response = batch(token, payload, Map.of());
            assertError(response, 400, "INVALID_INTERNAL_REQUEST");
        }
        assertEquals(200, batch(token, body(maximal), Map.of()).getStatusCode().value());
        assertEquals(
                200,
                batch(token, body(List.of(student.getId())), Map.of())
                        .getStatusCode()
                        .value());
    }

    @Test
    void transportMetadataIsRejectedAndNeverMutatesState() {
        UserAccountEntity student = student("transport-" + UUID.randomUUID(), "Stable Name");
        String token = service(allowed);
        String valid = body(List.of(student.getId()));
        assertError(batch(token, valid, Map.of("Cookie", "session=forbidden")), 400, "INVALID_INTERNAL_REQUEST");
        assertError(
                batch(token, valid, Map.of("X-User-Id", student.getId().toString())), 400, "INVALID_INTERNAL_REQUEST");
        assertError(batch(token, valid, Map.of("X-User-Roles", "STUDENT")), 400, "INVALID_INTERNAL_REQUEST");
        assertError(
                batch(token, valid, Map.of("X-Service-Identity", "assessment-service")),
                400,
                "INVALID_INTERNAL_REQUEST");
        assertError(batch(token, valid, Map.of("X-Authenticated-User", "spoof")), 400, "INVALID_INTERNAL_REQUEST");
        assertError(batch(token, valid, Map.of("X-Quizopia-Other", "spoof")), 400, "INVALID_INTERNAL_REQUEST");
        assertError(batch(token, valid, Map.of("Content-Type", "text/plain")), 400, "INVALID_INTERNAL_REQUEST");
        HttpHeaders duplicateType = headers(token);
        duplicateType.add("Content-Type", "application/json");
        duplicateType.add("Content-Type", "application/json");
        assertError(
                http.exchange(url(BATCH_PATH), HttpMethod.POST, new HttpEntity<>(valid, duplicateType), String.class),
                400,
                "INVALID_INTERNAL_REQUEST");
        var query = http.exchange(
                url(BATCH_PATH + "?access_token=forbidden"),
                HttpMethod.POST,
                new HttpEntity<>(valid, headers(token)),
                String.class);
        assertError(query, 400, "INVALID_INTERNAL_REQUEST");
        assertEquals(
                "Stable Name",
                jdbc.queryForObject(
                        "SELECT display_name FROM user_account WHERE id = ?", String.class, student.getId()));
        assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "SELECT account_status FROM user_account WHERE id = ?", String.class, student.getId()));
    }

    UserAccountEntity student(String username, String displayName) {
        var user = new UserAccountEntity("profiles-" + UUID.randomUUID() + "@gmail.com", username);
        user.setAccountStatus("ACTIVE");
        user.setEmailVerifiedAt(Instant.now());
        if (displayName != null) {
            user.setDisplayName(displayName);
        }
        accounts.saveAndFlush(user);
        roles.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        return user;
    }

    String body(List<UUID> ids) {
        try {
            return mapper.writeValueAsString(
                    Map.of("studentIds", ids.stream().map(UUID::toString).toList()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    ResponseEntity<String> batch(String token, String body, Map<String, String> extra) {
        var request = headers(token);
        extra.forEach(request::set);
        return http.exchange(url(BATCH_PATH), HttpMethod.POST, new HttpEntity<>(body, request), String.class);
    }

    HttpHeaders headers(String token) {
        var result = new HttpHeaders();
        if (token != null) {
            result.setBearerAuth(token);
        }
        result.setContentType(MediaType.APPLICATION_JSON);
        return result;
    }

    String service(ServiceClientRegistration client) {
        var headers = new HttpHeaders();
        headers.setBasicAuth(client.clientId(), client.clientSecret().value());
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add(
                "scope",
                clients.findByClientId(client.clientId()).orElseThrow().scopes().stream()
                        .sorted()
                        .collect(Collectors.joining(" ")));
        var result =
                http.exchange(url("/oauth2/token"), HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        assertEquals(200, result.getStatusCode().value(), result.getBody());
        return mapper.readTree(result.getBody()).path("access_token").asString();
    }

    String signed(String sub, String type, Map<String, Object> claims, String issuer, Instant iat, Instant exp) {
        var builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(sub)
                .issuedAt(iat)
                .expiresAt(exp)
                .claim(QuizopiaTokenClaims.PRINCIPAL_TYPE, type);
        claims.forEach(builder::claim);
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).build(), builder.build()))
                .getTokenValue();
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    void assertError(ResponseEntity<String> response, int status, String code) {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        assertEquals("{\"code\":\"" + code + "\"}", response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNull(response.getHeaders().getFirst("Set-Cookie"));
    }

    static String corrupt(String token) {
        var parts = token.split("\\.");
        return parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? 'B' : 'A') + parts[2].substring(1);
    }
}
