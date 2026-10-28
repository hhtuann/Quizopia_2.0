package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.identity.application.admission.UserAdmissionService;
import com.quizopia.identity.application.admission.UserAuthorityReconciler;
import com.quizopia.identity.application.serviceclient.ServiceClientRegistration;
import com.quizopia.identity.application.serviceclient.ServiceClientRegistrationService;
import com.quizopia.identity.configuration.UserAdmissionConfiguration;
import com.quizopia.identity.infrastructure.admission.RedisUserAuthorityStore;
import com.quizopia.identity.infrastructure.admission.UserAuthorityCodec;
import com.quizopia.identity.persistence.admission.PrimaryUserAuthorityQuery;
import com.quizopia.identity.persistence.admission.UserAuthorityWorkStore;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.token.UserAccessTokenIssuer;
import com.quizopia.identity.support.TestRsaKeyMaterial;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserAdmissionIntegrationTest {
    static final String ISSUER = "https://identity.admission.test";
    static final TestRsaKeyMaterial KEYS = TestRsaKeyMaterial.create("admission-test-");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> POSTGRES.getJdbcUrl() + "&socketTimeout=3&connectTimeout=2&cancelSignalTimeout=1");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "1s");
        registry.add("spring.data.redis.connect-timeout", () -> "1s");
        registry.add("quizopia.identity.security.authorization-server.enabled", () -> true);
        registry.add("quizopia.identity.security.authorization-server.issuer", () -> ISSUER);
        registry.add("quizopia.identity.security.signing-key.kid", () -> "admission-test");
        registry.add("quizopia.identity.security.signing-key.private-key-path", () -> KEYS.privateKeyPath()
                .toString());
        registry.add("quizopia.identity.security.signing-key.public-key-path", () -> KEYS.publicKeyPath()
                .toString());
        registry.add("quizopia.identity.proctoring-students.enabled", () -> true);
        registry.add("quizopia.identity.proctoring-students.require-secure-transport", () -> false);
        registry.add("quizopia.identity.notice-recipients.enabled", () -> true);
        registry.add("quizopia.identity.notice-recipients.require-secure-transport", () -> false);
        registry.add("quizopia.identity.user-admission.enabled", () -> true);
        registry.add("quizopia.identity.user-admission.require-secure-transport", () -> false);
        registry.add("quizopia.identity.user-admission.allowed-clients", () -> "assessment-service,quiz-service");
        registry.add("quizopia.identity.user-admission.batch-size", () -> 5);
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
    StringRedisTemplate redis;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    UserRoleRepository roles;

    @Autowired
    UserAccessTokenIssuer issuer;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    ServiceClientRegistrationService clients;

    @Autowired
    PrimaryUserAuthorityQuery primary;

    @Autowired
    RedisUserAuthorityStore projection;

    @Autowired
    UserAuthorityCodec codec;

    @Autowired
    UserAdmissionService admission;

    @Autowired
    UserAuthorityReconciler reconciler;

    @Autowired
    UserAuthorityWorkStore work;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    Flyway flyway;

    @Autowired
    com.quizopia.identity.configuration.UserAdmissionProperties admissionProperties;

    @Autowired
    com.quizopia.identity.application.refresh.RefreshSessionService refreshSessions;

    ServiceClientRegistration allowed;
    ServiceClientRegistration wrongScope;
    ServiceClientRegistration wrongClient;

    void registerClients() {
        allowed = clients.register(
                "assessment-service",
                Set.of(
                        UserAdmissionConfiguration.SCOPE,
                        "identity.notice-recipient.read",
                        "identity.proctoring-student.read"));
        wrongScope = clients.register("quiz-service", Set.of("quiz.read"));
        wrongClient = clients.register(
                "untrusted-service",
                Set.of(
                        UserAdmissionConfiguration.SCOPE,
                        "identity.notice-recipient.read",
                        "identity.proctoring-student.read"));
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE oauth2_service_client CASCADE");
        registerClients();
        jdbc.execute("TRUNCATE user_account CASCADE");
        jdbc.execute("TRUNCATE user_authority_work");
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @AfterAll
    static void cleanup() throws Exception {
        KEYS.close();
    }

    @Test
    @Order(1)
    void matchingFactsAndRealIssuedTokensAdmitOnlyIdentityAndCurrentRoles() throws Exception {
        var user = actor();
        String userToken = issuer.issue(user.getId()).value();
        projection.publish(primary.read(user.getId()));
        String serviceToken = service(allowed);
        var jwksResponse = http.getForEntity(url("/oauth2/jwks"), String.class);
        var key = (RSAKey) JWKSet.parse(jwksResponse.getBody()).getKeyByKeyId("admission-test");
        for (String token : List.of(userToken, serviceToken)) {
            var signed = SignedJWT.parse(token);
            assertEquals(JWSAlgorithm.RS256, signed.getHeader().getAlgorithm());
            assertTrue(signed.verify(new RSASSAVerifier(key.toRSAPublicKey())));
        }
        var response = request(serviceToken, userToken);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var body = mapper.readTree(response.getBody());
        assertEquals(user.getId().toString(), body.path("actorId").asText());
        assertEquals("STUDENT", body.path("roles").get(0).asText());
        assertEquals(2, body.size());
        assertFalse(response.getBody().contains(userToken));
        assertFalse(response.getBody().contains(user.getEmail()));
        assertTrue(primary.read(user.getId()).revokedBefore().isEmpty());
        assertEquals("14", flyway.info().current().getVersion().getVersion());
    }

    @Test
    @Order(1)
    void coldRedisAndPartialRestoreRemainPerActorFailClosedWithoutRequestRepair() {
        var first = actor();
        var second = actor();
        refreshSessions.issueInitial(first.getId(), Instant.now().plusSeconds(86400));
        refreshSessions.issueInitial(second.getId(), Instant.now().plusSeconds(86400));
        String caller = service(allowed);
        String firstToken = issuer.issue(first.getId()).value();
        assertError(request(caller, firstToken), 503, "AUTHORITY_UNAVAILABLE");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisUserAuthorityStore.key(first.getId()))));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token_family WHERE revoked_at IS NULL", Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token WHERE consumed_at IS NULL AND replaced_by_token_id IS NULL",
                        Integer.class));
        projection.publish(primary.read(first.getId()));
        assertEquals(200, request(caller, firstToken).getStatusCode().value());
        assertError(request(caller, issuer.issue(second.getId()).value()), 503, "AUTHORITY_UNAVAILABLE");
    }

    @Test
    @Order(1)
    void missingMalformedUnknownVersionAndWrongActorNeverAuthorize() {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        var facts = primary.read(user.getId());
        for (String value : List.of(
                "{}",
                codec.encode(facts).replaceFirst("1", "2"),
                codec.encode(facts).replace("|NONE", "|null"),
                codec.encode(primary.read(UUID.randomUUID())))) {
            redis.opsForValue().set(RedisUserAuthorityStore.key(user.getId()), value);
            assertError(request(caller, token), 503, "AUTHORITY_UNAVAILABLE");
        }
    }

    @Test
    @Order(1)
    void committedRevocationBeforePublicationAndRestoredOldProjectionDeny() throws Exception {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        var old = primary.read(user.getId());
        projection.publish(old);
        Instant issuedAt =
                SignedJWT.parse(token).getJWTClaimsSet().getIssueTime().toInstant();
        Instant cutoff = issuedAt.plusNanos(123456000);
        jdbc.update(
                "INSERT INTO user_access_revocation(user_id, revoked_before) VALUES (?, ?)",
                user.getId(),
                java.sql.Timestamp.from(cutoff));
        assertError(request(caller, token), 503, "AUTHORITY_UNAVAILABLE");
        reconciler.reconcileBatch();
        assertEquals(cutoff, projection.read(user.getId()).revokedBefore().orElseThrow());
        assertError(request(caller, token), 401, "USER_EVIDENCE_INVALID");
        projection.publish(old); // restored pre-revocation backup / delayed publisher
        assertError(request(caller, token), 503, "AUTHORITY_UNAVAILABLE");
    }

    @Test
    @Order(1)
    void missingPublicationHintAfterRevocationCannotAuthorizeAndSweepRecoversActor() {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        projection.publish(primary.read(user.getId()));
        jdbc.update("INSERT INTO user_access_revocation(user_id,revoked_before) VALUES (?,now())", user.getId());
        jdbc.execute("DELETE FROM user_authority_work");
        assertError(request(caller, token), 503, "AUTHORITY_UNAVAILABLE");
        var independent = new UserAuthorityReconciler(
                primary, projection, work, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        independent.reconcileBatch();
        assertError(request(caller, token), 401, "USER_EVIDENCE_INVALID");
    }

    @Test
    @Order(1)
    void exactCutoffIsInclusiveAndSubMillisecondPrecisionCannotBeLost() throws Exception {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        Instant iat = SignedJWT.parse(token).getJWTClaimsSet().getIssueTime().toInstant();
        for (Instant cutoff : List.of(iat, iat.plusNanos(1000), iat.minusNanos(1000))) {
            jdbc.update(
                    "INSERT INTO user_access_revocation(user_id, revoked_before) VALUES (?, ?) ON CONFLICT(user_id) DO UPDATE SET revoked_before=EXCLUDED.revoked_before",
                    user.getId(),
                    java.sql.Timestamp.from(cutoff));
            projection.publish(primary.read(user.getId()));
            assertEquals(cutoff, projection.read(user.getId()).revokedBefore().orElseThrow());
            assertEquals(
                    cutoff.isBefore(iat) ? 200 : 401,
                    request(service(allowed), token).getStatusCode().value());
        }
    }

    @Test
    @Order(1)
    void teacherGrantRequiresProjectionConvergenceAndNewJwtRoles() {
        var user = actor();
        String oldToken = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        projection.publish(primary.read(user.getId()));
        roles.saveAndFlush(new UserRoleEntity(user, UserRole.TEACHER));
        assertError(request(caller, oldToken), 503, "AUTHORITY_UNAVAILABLE");
        reconciler.reconcileBatch();
        assertError(request(caller, oldToken), 401, "USER_EVIDENCE_INVALID");
        var response = request(caller, issuer.issue(user.getId()).value());
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("TEACHER"));
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'TEACHER'", user.getId());
        assertError(
                request(caller, signedUser(user.getId(), List.of("STUDENT", "TEACHER"))), 503, "AUTHORITY_UNAVAILABLE");
        reconciler.reconcileBatch();
        assertError(
                request(caller, signedUser(user.getId(), List.of("STUDENT", "TEACHER"))), 401, "USER_EVIDENCE_INVALID");
    }

    @Test
    @Order(1)
    void activationVerificationAndDisabledNullOrRolelessAccountsFailClosed() {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        jdbc.update(
                "UPDATE user_account SET account_status = 'PENDING_EMAIL_VERIFICATION', email_verified_at = NULL WHERE id = ?",
                user.getId());
        reconciler.reconcileBatch();
        assertError(request(caller, token), 401, "USER_EVIDENCE_INVALID");
        jdbc.update(
                "UPDATE user_account SET account_status = 'ACTIVE', email_verified_at = now() WHERE id = ?",
                user.getId());
        assertError(request(caller, token), 503, "AUTHORITY_UNAVAILABLE");
        reconciler.reconcileBatch();
        assertEquals(200, request(caller, token).getStatusCode().value());
        for (String status : new String[] {"DISABLED", null}) {
            jdbc.update("UPDATE user_account SET account_status = ? WHERE id = ?", status, user.getId());
            reconciler.reconcileBatch();
            assertError(request(caller, token), 401, "USER_EVIDENCE_INVALID");
        }
        jdbc.update(
                "UPDATE user_account SET account_status = 'ACTIVE', email_verified_at = NULL WHERE id = ?",
                user.getId());
        reconciler.reconcileBatch();
        assertError(request(caller, token), 401, "USER_EVIDENCE_INVALID");
        jdbc.update("UPDATE user_account SET email_verified_at = now() WHERE id = ?", user.getId());
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", user.getId());
        reconciler.reconcileBatch();
        assertError(request(caller, token), 401, "USER_EVIDENCE_INVALID");
    }

    @Test
    @Order(1)
    void deletedOrNonexistentAccountCannotBecomeEligible() {
        UUID id = UUID.randomUUID();
        projection.publish(primary.read(id));
        assertError(request(service(allowed), signedUser(id, List.of("STUDENT"))), 401, "USER_EVIDENCE_INVALID");
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        projection.publish(primary.read(user.getId()));
        jdbc.update("DELETE FROM user_account WHERE id = ?", user.getId());
        assertError(request(service(allowed), token), 503, "AUTHORITY_UNAVAILABLE");
        reconciler.reconcileBatch();
        assertError(request(service(allowed), token), 401, "USER_EVIDENCE_INVALID");
    }

    @Test
    @Order(1)
    void durableQueueCoversRegistrationRoleActivationRevocationAndRollback() {
        var user = actor();
        var pending = work.pending();
        assertEquals(1, pending.size());
        work.acknowledge(pending.getFirst());
        assertTrue(work.pending().isEmpty());
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE user_account SET email_verified_at = NULL WHERE id = ?", user.getId());
            status.setRollbackOnly();
        });
        assertTrue(work.pending().isEmpty());
        jdbc.update("UPDATE user_account SET email_verified_at = NULL WHERE id = ?", user.getId());
        var first = work.pending().getFirst();
        roles.saveAndFlush(new UserRoleEntity(user, UserRole.TEACHER));
        work.acknowledge(first); // cannot discard newer durable work
        assertEquals(1, work.pending().size());
        jdbc.update("INSERT INTO user_access_revocation(user_id, revoked_before) VALUES (?, now())", user.getId());
        assertNotEquals(first.workId(), work.pending().getFirst().workId());
        reconciler.reconcileBatch();
        assertTrue(work.pending().isEmpty());
        assertEquals(primary.read(user.getId()), projection.read(user.getId()));
    }

    @Test
    @Order(1)
    void missingHookAndRedisLossRecoverByBoundedSweepAcrossFreshWorkerInstances() {
        for (int i = 0; i < 12; i++) actor();
        jdbc.execute("DELETE FROM user_authority_work"); // simulate missing hint/historical writer
        var fresh = new UserAuthorityReconciler(
                primary, projection, work, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        fresh.reconcileBatch();
        assertEquals(5, redis.keys("quizopia:identity:user-authority:v1:*").size());
        fresh.reconcileBatch();
        fresh.reconcileBatch();
        assertEquals(12, redis.keys("quizopia:identity:user-authority:v1:*").size());
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        var restarted = new UserAuthorityReconciler(
                primary, projection, work, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        restarted.reconcileBatch();
        restarted.reconcileBatch();
        restarted.reconcileBatch();
        assertEquals(12, redis.keys("quizopia:identity:user-authority:v1:*").size());
        assertEquals(12, jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class));
    }

    @Test
    @Order(1)
    void coherentPrimarySnapshotSeesOnlyCommittedFactsAndLaterAdmissionDeniesOldProjection() throws Exception {
        var user = actor();
        var old = primary.read(user.getId());
        projection.publish(old);
        var ready = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var mutation = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update(
                        "UPDATE user_account SET account_status = 'DISABLED', email_verified_at = NULL WHERE id = ?",
                        user.getId());
                jdbc.update(
                        "INSERT INTO user_role(id,user_id,role,created_at) VALUES (?,?,'TEACHER',now())",
                        UUID.randomUUID(),
                        user.getId());
                jdbc.update(
                        "INSERT INTO user_access_revocation(user_id,revoked_before) VALUES (?,now())", user.getId());
                ready.countDown();
                await(commit);
            }));
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                assertEquals(old, primary.read(user.getId()));
                assertEquals(
                        200,
                        request(service(allowed), signedUser(user.getId(), List.of("STUDENT")))
                                .getStatusCode()
                                .value());
            } finally {
                commit.countDown();
            }
            mutation.get(10, TimeUnit.SECONDS);
        }
        var current = primary.read(user.getId());
        assertEquals("DISABLED", current.accountStatus());
        assertFalse(current.emailVerified());
        assertEquals(List.of(UserRole.STUDENT, UserRole.TEACHER), current.roles());
        assertTrue(current.revokedBefore().isPresent());
        assertError(
                request(service(allowed), signedUser(user.getId(), List.of("STUDENT"))), 503, "AUTHORITY_UNAVAILABLE");
    }

    @Test
    @Order(1)
    void outerRepeatableReadCannotPinAdmissionToAnOldSnapshot() throws Exception {
        var user = actor();
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(status -> {
            assertEquals(
                    "ACTIVE",
                    jdbc.queryForObject(
                            "SELECT account_status FROM user_account WHERE id = ?", String.class, user.getId()));
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> jdbc.update(
                                "UPDATE user_account SET account_status='DISABLED' WHERE id=?", user.getId()))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            assertEquals(
                    "ACTIVE",
                    jdbc.queryForObject(
                            "SELECT account_status FROM user_account WHERE id = ?", String.class, user.getId()));
            assertEquals("DISABLED", primary.read(user.getId()).accountStatus());
        });
    }

    @Test
    @Order(1)
    void concurrentOldPublisherAndReconciliationCannotAuthorizeDifferentDurableState() throws Exception {
        var user = actor();
        var old = primary.read(user.getId());
        var snapshotReady = new CountDownLatch(1);
        var publish = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stalePublisher = executor.submit(() -> {
                var facts = primary.read(user.getId());
                snapshotReady.countDown();
                await(publish);
                projection.publish(facts);
            });
            assertTrue(snapshotReady.await(10, TimeUnit.SECONDS));
            jdbc.update("UPDATE user_account SET account_status='DISABLED' WHERE id=?", user.getId());
            reconciler.reconcileBatch();
            assertNotEquals(old, projection.read(user.getId()));
            publish.countDown();
            stalePublisher.get(10, TimeUnit.SECONDS);
        } finally {
            publish.countDown();
        }
        assertEquals(old, projection.read(user.getId()));
        assertError(
                request(service(allowed), signedUser(user.getId(), List.of("STUDENT"))), 503, "AUTHORITY_UNAVAILABLE");
        reconciler.reconcileBatch(); // wrap the local sweep cursor after the completed page
        reconciler.reconcileBatch();
        assertError(
                request(service(allowed), signedUser(user.getId(), List.of("STUDENT"))), 401, "USER_EVIDENCE_INVALID");
    }

    @Test
    @Order(1)
    void disabledAndInsecureTransportGatesFailClosedInActualSecurityChain() {
        admissionProperties.setEnabled(false);
        try {
            assertError(request(null, null), 503, "AUTHORITY_UNAVAILABLE");
        } finally {
            admissionProperties.setEnabled(true);
        }
        admissionProperties.setRequireSecureTransport(true);
        try {
            assertError(request(null, null), 503, "AUTHORITY_UNAVAILABLE");
        } finally {
            admissionProperties.setRequireSecureTransport(false);
        }
    }

    @Test
    @Order(1)
    void v12UpgradePreservesExistingActorsAndCapturesSubsequentChanges() throws Exception {
        String schema = "authority_upgrade";
        var old = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target("12")
                .load();
        old.migrate();
        UUID actor = UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO user_account(id,email,account_status,created_at,updated_at) VALUES (?,'upgrade-test@gmail.com','PENDING_EMAIL_VERIFICATION',now(),now())")) {
                insert.setObject(1, actor);
                insert.executeUpdate();
            }
            var upgrade = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load();
            upgrade.migrate();
            assertEquals("14", upgrade.info().current().getVersion().getVersion());
            try (var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT count(*) FROM user_authority_work")) {
                    result.next();
                    assertEquals(0, result.getInt(1));
                }
                statement.executeUpdate("UPDATE user_account SET account_status='ACTIVE',email_verified_at=now()");
                try (var result = statement.executeQuery("SELECT user_id FROM user_authority_work")) {
                    assertTrue(result.next());
                    assertEquals(actor, result.getObject(1, UUID.class));
                }
            }
        }
    }

    @Test
    @Order(1)
    void validServiceWrongClientOrScopeAndBrowserPrincipalAreForbidden() {
        var user = actor();
        String userToken = issuer.issue(user.getId()).value();
        for (String caller : List.of(service(wrongClient), service(wrongScope), userToken))
            assertError(request(caller, userToken), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(request(null, userToken), 401, "SERVICE_AUTHENTICATION_INVALID");
    }

    @Test
    @Order(1)
    void invalidServiceClaimsIssuerSignatureAndExpirationAreInternal401() {
        var user = actor();
        String userToken = issuer.issue(user.getId()).value();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (String token : List.of(
                signed(
                        "assessment-service",
                        "UNKNOWN",
                        Map.of("scope", List.of(UserAdmissionConfiguration.SCOPE)),
                        ISSUER,
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                signed("assessment-service", "SERVICE", Map.of(), ISSUER, now.minusSeconds(1), now.plusSeconds(60)),
                signed(
                        "assessment-service",
                        "SERVICE",
                        Map.of("scope", UserAdmissionConfiguration.SCOPE),
                        ISSUER,
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                signed(
                        "assessment-service",
                        "SERVICE",
                        Map.of("scope", List.of(UserAdmissionConfiguration.SCOPE), "roles", List.of("STUDENT")),
                        ISSUER,
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                signed(
                        "assessment-service",
                        "SERVICE",
                        Map.of("scope", List.of(UserAdmissionConfiguration.SCOPE)),
                        "https://wrong.test",
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                signed(
                        "assessment-service",
                        "SERVICE",
                        Map.of("scope", List.of(UserAdmissionConfiguration.SCOPE)),
                        ISSUER,
                        now.minusSeconds(60),
                        now.minusSeconds(1)),
                corrupt(service(allowed))))
            assertError(request(token, userToken), 401, "SERVICE_AUTHENTICATION_INVALID");
    }

    @Test
    @Order(1)
    void userEvidenceIsIndependentlyValidatedIncludingPrincipalIssuerSignatureExpiryAndClaims() {
        var user = actor();
        String caller = service(allowed);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        projection.publish(primary.read(user.getId()));
        for (String evidence : List.of(
                service(allowed),
                signed(
                        user.getId().toString(),
                        "USER",
                        Map.of("roles", List.of("STUDENT")),
                        "https://wrong.test",
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                signed(
                        user.getId().toString(),
                        "USER",
                        Map.of("roles", List.of("STUDENT")),
                        ISSUER,
                        now.minusSeconds(60),
                        now.minusSeconds(1)),
                signed(
                        user.getId().toString(),
                        "USER",
                        Map.of("roles", List.of("STUDENT")),
                        ISSUER,
                        now.plusSeconds(30),
                        now.plusSeconds(60)),
                signed(
                        "0-0-0-0-1",
                        "USER",
                        Map.of("roles", List.of("STUDENT")),
                        ISSUER,
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                signed(
                        user.getId().toString(),
                        "USER",
                        Map.of("roles", List.of("STUDENT"), "scope", List.of("test.read")),
                        ISSUER,
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                signed(
                        user.getId().toString(),
                        "USER",
                        Map.of("roles", List.of("ALIEN")),
                        ISSUER,
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                corrupt(issuer.issue(user.getId()).value())))
            assertError(request(caller, evidence), 401, "USER_EVIDENCE_INVALID");
    }

    @Test
    @Order(1)
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void duplicateAmbiguousOrMissingEvidenceAndCredentialsAreRejectedAndRedacted(
            org.springframework.boot.test.system.CapturedOutput output) {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        assertError(request(caller, null), 400, "INVALID_ADMISSION_REQUEST");
        for (List<String> values : List.of(
                List.of(token, token), List.of(token + "," + token), List.of("Bearer " + token), List.of("bad"))) {
            var headers = headers(caller, null);
            headers.put(UserAdmissionConfiguration.USER_HEADER, values);
            assertError(exchange(headers, null, ""), 400, "INVALID_ADMISSION_REQUEST");
        }
        var headers = headers(caller, token);
        headers.add("Authorization", "Bearer " + caller);
        assertError(exchange(headers, null, ""), 401, "SERVICE_AUTHENTICATION_INVALID");
        assertError(exchange(headers(caller, token), "{}", ""), 400, "INVALID_ADMISSION_REQUEST");
        assertError(
                exchange(headers(caller, token), null, "?access_token=forbidden-query-evidence"),
                400,
                "INVALID_ADMISSION_REQUEST");
        var cookies = headers(caller, token);
        cookies.add("Cookie", "quizopia_refresh=sensitive-test-value");
        assertError(exchange(cookies, null, ""), 400, "INVALID_ADMISSION_REQUEST");
        assertFalse(output.getAll().contains(token));
        assertFalse(output.getAll().contains(caller));
        assertFalse(output.getAll().contains("sensitive-test-value"));
    }

    @Test
    @Order(98)
    void redisTimeoutDeniesAndPublicationGapSurvivesRestartRecovery() throws Exception {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        projection.publish(primary.read(user.getId()));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertError(request(caller, token), 503, "AUTHORITY_UNAVAILABLE");
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
            jdbc.update("UPDATE user_account SET account_status='DISABLED' WHERE id=?", user.getId());
            reconciler.reconcileBatch();
            assertFalse(work.pending().isEmpty());
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        // A timed-out Redis SET can still execute after connectivity returns. Both outcomes deny.
        var afterTimeout = request(caller, token);
        assertTrue(afterTimeout.getStatusCode().value() == 503
                || afterTimeout.getStatusCode().value() == 401);
        var restart = new UserAuthorityReconciler(
                primary, projection, work, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        restart.reconcileBatch();
        assertTrue(work.pending().isEmpty());
        assertError(request(caller, token), 401, "USER_EVIDENCE_INVALID");
    }

    @Test
    @Order(99)
    void primaryOutageAndStatementTimeoutDenyWithoutAnyRedisFallback() {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        projection.publish(primary.read(user.getId()));
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertError(request(caller, token), 503, "AUTHORITY_UNAVAILABLE");
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 15);
        } finally {
            POSTGRES.getDockerClient()
                    .unpauseContainerCmd(POSTGRES.getContainerId())
                    .exec();
        }
    }

    @Test
    @Order(100)
    void disconnectedRedisNeverFallsBackToDurableOnlyAdmission() {
        var user = actor();
        String token = issuer.issue(user.getId()).value();
        String caller = service(allowed);
        projection.publish(primary.read(user.getId()));
        REDIS.stop();
        assertError(request(caller, token), 503, "AUTHORITY_UNAVAILABLE");
        assertTrue(primary.read(user.getId()).eligible());
    }

    UserAccountEntity actor() {
        var user = new UserAccountEntity("admission-" + UUID.randomUUID() + "@gmail.com", "actor-" + UUID.randomUUID());
        user.setAccountStatus("ACTIVE");
        user.setEmailVerifiedAt(Instant.now());
        accounts.saveAndFlush(user);
        roles.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        return user;
    }

    ResponseEntity<String> proctoring(UUID id, String token, String body, Map<String, String> extra) {
        var headers = headers(token, null);
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        return http.exchange(
                url("/internal/security/proctoring-students/" + id + "/eligibility"),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    @Test
    @Order(1)
    void proctoringEligibilityUsesCurrentFactsAndAttemptStartRevocationFence() {
        var user = actor();
        projection.publish(primary.read(user.getId()));
        var started = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        String request = "{\"attemptStartedAt\":\"" + started + "\"}";
        String token = service(allowed);
        var response = proctoring(user.getId(), token, request, Map.of());
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var body = mapper.readTree(response.getBody());
        assertEquals(4, body.size());
        assertEquals(user.getId().toString(), body.path("studentId").asString());
        assertEquals(started.toString(), body.path("attemptStartedAt").asString());
        assertTrue(body.path("eligible").asBoolean());
        assertFalse(Instant.parse(body.path("checkedAt").asString()).isAfter(Instant.now()));
        assertFalse(response.getBody().contains(user.getEmail()));
        jdbc.update(
                "INSERT INTO user_access_revocation(user_id,revoked_before) VALUES(?,?)",
                user.getId(),
                java.sql.Timestamp.from(started));
        assertError(proctoring(user.getId(), token, request, Map.of()), 503, "AUTHORITY_UNAVAILABLE");
        projection.publish(primary.read(user.getId()));
        assertFalse(mapper.readTree(
                        proctoring(user.getId(), token, request, Map.of()).getBody())
                .path("eligible")
                .asBoolean());
        String later = "{\"attemptStartedAt\":\"" + started.plusSeconds(1) + "\"}";
        assertTrue(
                mapper.readTree(proctoring(user.getId(), token, later, Map.of()).getBody())
                        .path("eligible")
                        .asBoolean());
        jdbc.update("UPDATE user_account SET account_status='DISABLED' WHERE id=?", user.getId());
        assertError(proctoring(user.getId(), token, request, Map.of()), 503, "AUTHORITY_UNAVAILABLE");
        projection.publish(primary.read(user.getId()));
        assertFalse(
                mapper.readTree(proctoring(user.getId(), token, later, Map.of()).getBody())
                        .path("eligible")
                        .asBoolean());
    }

    @Test
    @Order(1)
    void proctoringCapabilityIsServiceOnlyAndNotInterchangeableWithNoticeScope() {
        var user = actor();
        projection.publish(primary.read(user.getId()));
        String body = "{\"attemptStartedAt\":\"" + Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS) + "\"}";
        var now = Instant.now();
        String noticeOnly = signed(
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of("identity.notice-recipient.read")),
                ISSUER,
                now.minusSeconds(1),
                now.plusSeconds(60));
        String targetOnly = signed(
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of("identity.proctoring-student.read")),
                ISSUER,
                now.minusSeconds(1),
                now.plusSeconds(60));
        assertError(proctoring(user.getId(), noticeOnly, body, Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(recipient(user.getId(), targetOnly, Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertEquals(
                200,
                proctoring(user.getId(), targetOnly, body, Map.of())
                        .getStatusCode()
                        .value());
        assertError(proctoring(user.getId(), service(wrongClient), body, Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(proctoring(user.getId(), service(wrongScope), body, Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(
                proctoring(user.getId(), issuer.issue(user.getId()).value(), body, Map.of()),
                403,
                "SERVICE_CALLER_FORBIDDEN");
        assertError(proctoring(user.getId(), null, body, Map.of()), 401, "SERVICE_AUTHENTICATION_INVALID");
        assertError(
                proctoring(user.getId(), corrupt(service(allowed)), body, Map.of()),
                401,
                "SERVICE_AUTHENTICATION_INVALID");
        String expired = signed(
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of("identity.proctoring-student.read")),
                ISSUER,
                now.minusSeconds(120),
                now.minusSeconds(1));
        assertError(proctoring(user.getId(), expired, body, Map.of()), 401, "SERVICE_AUTHENTICATION_INVALID");
        assertError(
                proctoring(
                        user.getId(),
                        targetOnly,
                        body,
                        Map.of(
                                UserAdmissionConfiguration.USER_HEADER,
                                issuer.issue(user.getId()).value())),
                400,
                "INVALID_INTERNAL_REQUEST");
    }

    @Test
    @Order(1)
    void proctoringRequestShapesAreStrictSanitizedAndMutationFree() throws Exception {
        var user = actor();
        projection.publish(primary.read(user.getId()));
        String token = service(allowed);
        String value =
                Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS).toString();
        String valid = "{\"attemptStartedAt\":\"" + value + "\"}";
        var original = primary.read(user.getId());
        for (String body : List.of(
                "",
                "{}",
                "[]",
                "null",
                "{\"attemptStartedAt\":123}",
                "{\"attemptStartedAt\":null}",
                "{\"attemptStartedAt\":true}",
                "{\"attemptStartedAt\":\"invalid\"}",
                "{\"attemptStartedAt\":\"0000-01-01T00:00:00Z\"}",
                "{\"attemptStartedAt\":\"2020-01-01T00:00:00.000000001Z\"}",
                "{\"attemptStartedAt\":\"2020-01-01T00:00:00+00:00\"}",
                "{\"attemptStartedAt\":\"" + Instant.now().plusSeconds(60) + "\"}",
                "{\"attemptStartedAt\":\"" + value + "\",\"attemptStartedAt\":\"" + value + "\"}",
                "{\"attemptStartedAt\":\"" + value + "\",\"extra\":false}",
                valid + " {}",
                " ".repeat(257))) {
            var response = proctoring(user.getId(), token, body, Map.of());
            assertError(response, 400, "INVALID_INTERNAL_REQUEST");
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertEquals(original, primary.read(user.getId()));
            assertEquals(original, projection.read(user.getId()));
        }
        for (String header : List.of(
                "Cookie",
                "X-User-Id",
                "X-User-Roles",
                "X-Service-Identity",
                "X-Authenticated-User",
                "X-Quizopia-Other"))
            assertError(
                    proctoring(user.getId(), token, valid, Map.of(header, "invalid")), 400, "INVALID_INTERNAL_REQUEST");
        assertError(
                proctoring(user.getId(), token, valid, Map.of("Content-Type", "text/plain")),
                400,
                "INVALID_INTERNAL_REQUEST");
        for (List<String> contentTypes : List.of(
                List.<String>of(),
                List.of("application/json", "application/json"),
                List.of("application/*"),
                List.of("*/*"),
                List.of("application/json;charset=ISO-8859-1"),
                List.of("application/json;extra=invalid"),
                List.of("application/json;charset=UTF-8;charset=UTF-8"))) {
            var wire = proctoringWire(user.getId(), token, valid, contentTypes, null);
            assertEquals(400, wire.statusCode(), wire.body());
            assertEquals("{\"code\":\"INVALID_INTERNAL_REQUEST\"}", wire.body());
            assertEquals("no-store", wire.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(original, primary.read(user.getId()));
            assertEquals(original, projection.read(user.getId()));
        }
        var encoded = proctoringWire(user.getId(), token, valid, List.of("application/json"), "gzip");
        assertEquals(400, encoded.statusCode(), encoded.body());
        assertEquals("{\"code\":\"INVALID_INTERNAL_REQUEST\"}", encoded.body());
        assertEquals(
                200,
                proctoringWire(user.getId(), token, valid, List.of("application/json;charset=UTF-8"), null)
                        .statusCode());
        var headers = headers(token, null);
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertError(
                http.exchange(
                        url("/internal/security/proctoring-students/" + user.getId() + "/eligibility?other=true"),
                        HttpMethod.POST,
                        new HttpEntity<>(valid, headers),
                        String.class),
                400,
                "INVALID_INTERNAL_REQUEST");
        assertEquals(
                200,
                proctoring(user.getId(), token, valid, Map.of()).getStatusCode().value());
    }

    @Test
    @Order(1)
    void proctoringMissingUnverifiedRemovedRoleAndAuthorityRemainFailClosed() {
        var user = actor();
        String token = service(allowed);
        String body = "{\"attemptStartedAt\":\"" + Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS) + "\"}";
        assertError(proctoring(user.getId(), token, body, Map.of()), 503, "AUTHORITY_UNAVAILABLE");
        jdbc.update("UPDATE user_account SET email_verified_at=NULL WHERE id=?", user.getId());
        projection.publish(primary.read(user.getId()));
        assertFalse(
                mapper.readTree(proctoring(user.getId(), token, body, Map.of()).getBody())
                        .path("eligible")
                        .asBoolean());
        jdbc.update("UPDATE user_account SET email_verified_at=now() WHERE id=?", user.getId());
        jdbc.update("DELETE FROM user_role WHERE user_id=?", user.getId());
        projection.publish(primary.read(user.getId()));
        assertFalse(
                mapper.readTree(proctoring(user.getId(), token, body, Map.of()).getBody())
                        .path("eligible")
                        .asBoolean());
        UUID missing = UUID.randomUUID();
        projection.publish(primary.read(missing));
        assertFalse(mapper.readTree(proctoring(missing, token, body, Map.of()).getBody())
                .path("eligible")
                .asBoolean());
    }

    java.net.http.HttpResponse<String> proctoringWire(
            UUID id, String token, String body, List<String> types, String encoding) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(url("/internal/security/proctoring-students/" + id + "/eligibility")))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        types.forEach(type -> request.header("Content-Type", type));
        if (encoding != null) request.header("Content-Encoding", encoding);
        try (var client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build()) {
            return client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }

    ResponseEntity<String> recipient(UUID id, String token, Map<String, String> extra) {
        var headers = headers(token, null);
        extra.forEach(headers::set);
        return http.exchange(
                url("/internal/security/notice-recipients/" + id + "/eligibility"),
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                String.class);
    }

    @Test
    @Order(1)
    void noticeEligibilityMinimalRealServiceAndPrimaryRedisEquality() {
        var user = actor();
        projection.publish(primary.read(user.getId()));
        var token = service(allowed);
        var response = recipient(user.getId(), token, Map.of());
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        var body = mapper.readTree(response.getBody());
        assertEquals(3, body.size());
        assertEquals(user.getId().toString(), body.path("studentId").asText());
        assertTrue(body.path("eligible").asBoolean());
        assertFalse(Instant.parse(body.path("checkedAt").asText()).isAfter(Instant.now()));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        jdbc.update("UPDATE user_account SET account_status='DISABLED' WHERE id=?", user.getId());
        assertError(recipient(user.getId(), token, Map.of()), 503, "AUTHORITY_UNAVAILABLE");
        projection.publish(primary.read(user.getId()));
        assertFalse(mapper.readTree(recipient(user.getId(), token, Map.of()).getBody())
                .path("eligible")
                .asBoolean());
        var missing = UUID.randomUUID();
        projection.publish(primary.read(missing));
        assertFalse(mapper.readTree(recipient(missing, token, Map.of()).getBody())
                .path("eligible")
                .asBoolean());
    }

    @Test
    @Order(1)
    void noticeRequiresExactAssessmentScopeServiceAndRejectsUserEvidence() {
        var user = actor();
        projection.publish(primary.read(user.getId()));
        assertError(recipient(user.getId(), service(wrongClient), Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(recipient(user.getId(), service(wrongScope), Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(
                recipient(user.getId(), issuer.issue(user.getId()).value(), Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(recipient(user.getId(), null, Map.of()), 401, "SERVICE_AUTHENTICATION_INVALID");
        assertError(
                recipient(user.getId(), corrupt(service(allowed)), Map.of()), 401, "SERVICE_AUTHENTICATION_INVALID");
        assertError(
                recipient(
                        user.getId(),
                        service(allowed),
                        Map.of(
                                UserAdmissionConfiguration.USER_HEADER,
                                issuer.issue(user.getId()).value())),
                400,
                "INVALID_INTERNAL_REQUEST");
        String missingScope = signed(
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of(UserAdmissionConfiguration.SCOPE)),
                ISSUER,
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(120));
        assertError(recipient(user.getId(), missingScope, Map.of()), 403, "SERVICE_CALLER_FORBIDDEN");
        assertError(
                recipient(
                        user.getId(),
                        service(allowed),
                        Map.of("X-User-Id", user.getId().toString())),
                400,
                "INVALID_INTERNAL_REQUEST");
    }

    @Test
    @Order(1)
    void noticeUnverifiedRemovedRoleAndMissingRedisFailClosed() {
        var user = actor();
        var token = service(allowed);
        assertError(recipient(user.getId(), token, Map.of()), 503, "AUTHORITY_UNAVAILABLE");
        jdbc.update("UPDATE user_account SET email_verified_at=NULL WHERE id=?", user.getId());
        projection.publish(primary.read(user.getId()));
        assertFalse(mapper.readTree(recipient(user.getId(), token, Map.of()).getBody())
                .path("eligible")
                .asBoolean());
        jdbc.update("UPDATE user_account SET email_verified_at=now() WHERE id=?", user.getId());
        jdbc.update("DELETE FROM user_role WHERE user_id=?", user.getId());
        projection.publish(primary.read(user.getId()));
        assertFalse(mapper.readTree(recipient(user.getId(), token, Map.of()).getBody())
                .path("eligible")
                .asBoolean());
    }

    @Test
    @Order(1)
    void noticeBodyQueryAndCookieAreSanitizedAndMutationFree() {
        var user = actor();
        projection.publish(primary.read(user.getId()));
        String token = service(allowed);
        String path = "/internal/security/notice-recipients/" + user.getId() + "/eligibility";
        var body =
                http.exchange(url(path), HttpMethod.POST, new HttpEntity<>("{}", headers(token, null)), String.class);
        assertError(body, 400, "INVALID_INTERNAL_REQUEST");
        var query = http.exchange(
                url(path + "?recipient=other"),
                HttpMethod.POST,
                new HttpEntity<>(null, headers(token, null)),
                String.class);
        assertError(query, 400, "INVALID_INTERNAL_REQUEST");
        assertError(
                recipient(user.getId(), token, Map.of("Cookie", "session=invalid")), 400, "INVALID_INTERNAL_REQUEST");
        assertEquals(
                200, recipient(user.getId(), token, Map.of()).getStatusCode().value());
    }

    String signedUser(UUID id, List<String> roles) {
        return signed(
                id.toString(),
                "USER",
                Map.of("roles", roles),
                ISSUER,
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(120));
    }

    String signed(String sub, String type, Map<String, Object> claims, String issuer, Instant iat, Instant exp) {
        var builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(sub)
                .issuedAt(iat)
                .expiresAt(exp)
                .claim("principal_type", type);
        claims.forEach(builder::claim);
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).build(), builder.build()))
                .getTokenValue();
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
                        .collect(java.util.stream.Collectors.joining(" ")));
        var result =
                http.exchange(url("/oauth2/token"), HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        assertEquals(200, result.getStatusCode().value(), result.getBody());
        return mapper.readTree(result.getBody()).path("access_token").asText();
    }

    HttpHeaders headers(String caller, String user) {
        var result = new HttpHeaders();
        if (caller != null) result.setBearerAuth(caller);
        if (user != null) result.set(UserAdmissionConfiguration.USER_HEADER, user);
        return result;
    }

    ResponseEntity<String> request(String caller, String user) {
        return exchange(headers(caller, user), null, "");
    }

    ResponseEntity<String> exchange(HttpHeaders headers, String body, String query) {
        return http.exchange(
                url(UserAdmissionConfiguration.PATH + query),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
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

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency barrier expired");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency barrier interrupted");
        }
    }
}
