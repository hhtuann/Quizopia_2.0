package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.identity.application.admission.AdmissionFailure;
import com.quizopia.identity.application.admission.UserAdmissionService;
import com.quizopia.identity.application.admission.UserAuthorityReconciler;
import com.quizopia.identity.infrastructure.admission.RedisUserAuthorityStore;
import com.quizopia.identity.persistence.admission.PrimaryUserAuthorityQuery;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class UserAuthorityRestartIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @Test
    void applicationRestartRecoversCommittedGapAndZeroUuidWithoutPostCommitHint() throws Exception {
        UUID actor = new UUID(0, 0);
        Instant issuedAt = Instant.now().minusSeconds(60);
        try (var first = start()) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            jdbc.update(
                    "INSERT INTO user_account(id,email,account_status,email_verified_at,created_at,updated_at) VALUES (?,'restart-test@gmail.com','ACTIVE',now(),now(),now())",
                    actor);
            jdbc.update(
                    "INSERT INTO user_role(id,user_id,role,created_at) VALUES (?,?,'STUDENT',now())",
                    UUID.randomUUID(),
                    actor);
            var primary = first.getBean(PrimaryUserAuthorityQuery.class);
            first.getBean(RedisUserAuthorityStore.class).publish(primary.read(actor));
            first.getBean(UserAdmissionService.class).admit(actor, issuedAt, List.of("STUDENT"));
            jdbc.update(
                    "INSERT INTO user_access_revocation(user_id,revoked_before) VALUES (?,?)",
                    actor,
                    Timestamp.from(Instant.now()));
            // Stop the complete application after durable commit, before any publisher runs.
            // Same durable gap as process death: neither after-commit hint nor worker was invoked.
        }
        try (var restarted = start()) {
            var service = restarted.getBean(UserAdmissionService.class);
            assertEquals(
                    503,
                    assertThrows(AdmissionFailure.class, () -> service.admit(actor, issuedAt, List.of("STUDENT")))
                            .status());
            restarted.getBean(UserAuthorityReconciler.class).reconcileBatch();
            assertEquals(
                    401,
                    assertThrows(AdmissionFailure.class, () -> service.admit(actor, issuedAt, List.of("STUDENT")))
                            .status());
            var jdbc = restarted.getBean(JdbcTemplate.class);
            jdbc.execute("DELETE FROM user_authority_work");
            restarted
                    .getBean(org.springframework.data.redis.core.StringRedisTemplate.class)
                    .delete(RedisUserAuthorityStore.key(actor));
            restarted.getBean(UserAuthorityReconciler.class).reconcileBatch();
            restarted.getBean(UserAuthorityReconciler.class).reconcileBatch();
            // The first page includes UUID zero; the sweep also recovers without queue hints.
            assertEquals(
                    restarted.getBean(PrimaryUserAuthorityQuery.class).read(actor),
                    restarted.getBean(RedisUserAuthorityStore.class).read(actor));
            int port = ((ServletWebServerApplicationContext) restarted)
                    .getWebServer()
                    .getPort();
            var response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(
                                            "http://localhost:" + port + "/internal/security/user-admissions"))
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode());
            assertEquals("{\"code\":\"AUTHORITY_UNAVAILABLE\"}", response.body());
            assertEquals(
                    "no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(IdentityServiceApplication.class)
                .profiles("persistence-test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--quizopia.identity.user-admission.enabled=false",
                        "--quizopia.identity.user-admission.reconciliation-enabled=false");
    }
}
