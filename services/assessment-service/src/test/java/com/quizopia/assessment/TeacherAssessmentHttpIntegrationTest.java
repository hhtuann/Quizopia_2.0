package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.persistence.JdbcPublicationStore;
import com.quizopia.assessment.security.admission.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;

/** Signed RS256 HTTP + actual service/database adapters; remote admission/CLASS are bounded stubs. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.publication.enabled=true",
            "logging.level.org.springframework.test.web.reactive.server.ExchangeResult=OFF",
            "quizopia.assessment.features.results-enabled=true",
            "quizopia.assessment.features.attempts-enabled=true",
            "quizopia.assessment.closure.cursor-keys.v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
@Import(ClosedBusinessBoundaryTest.Keys.class)
class TeacherAssessmentHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_assessment_roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
        p.add("spring.flyway.user", DB::getUsername);
        p.add("spring.flyway.password", DB::getPassword);
    }

    @Autowired
    JdbcTemplate db;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    AttemptFinalization finalizer;

    @MockitoBean
    IdentityAdmissionClient admission;

    @MockitoBean
    PublicationSources sources;
    // Explicit synthetic-only override in test sources, never a runtime activation flag.
    @MockitoBean
    SensitiveAssessmentPolicy syntheticPolicy;

    @Value("${local.server.port}")
    int port;

    AtomicFinalizationIntegrationTest f;
    UUID publication, attempt;
    String base;
    WebTestClient http;

    @BeforeEach
    void seed() {
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
        when(syntheticPolicy.expiresAt(any())).thenAnswer(i -> ((Instant) i.getArgument(0)).plusSeconds(3600));
        when(syntheticPolicy.disclosureAvailable()).thenReturn(true);
        f = new AtomicFinalizationIntegrationTest();
        f.db = db;
        f.manager = new org.springframework.jdbc.support.JdbcTransactionManager(db.getDataSource());
        f.core = new com.quizopia.assessment.persistence.JdbcAttemptFinalization(db, f.manager);
        f.student = UUID.fromString(ACTOR);
        Instant now = f.now();
        var config = new PublicationConfiguration(
                "T2 HTTP",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                PublicationConfiguration.Audience.PUBLIC,
                null,
                now.minusSeconds(600),
                now.plusSeconds(600),
                0,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
        var store = new JdbcPublicationStore(db, transactions, Clock.systemUTC());
        var p = store.create(UUID.fromString(ACTOR), UUID.randomUUID().toString(), config);
        store.open(
                UUID.fromString(ACTOR),
                p.id(),
                0,
                new String(
                        SnapshotFixtures.snapshot(config, "protected synthetic source"),
                        java.nio.charset.StandardCharsets.UTF_8));
        publication = p.id();
        attempt = f.active(new AtomicFinalizationIntegrationTest.Seed(publication, config), 0);
        base = "/api/assessments/publications/" + publication;
        http = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    String token(String subject, String type, List<String> roles) {
        return sign(
                KEY,
                ISSUER,
                subject,
                type,
                Map.of("roles", roles),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    void submit() {
        f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
    }

    @Test
    void ownerSummaryDirectoryHistoryAndSubmittedReviewHaveExactSafeWireShapes() {
        submit();
        http.get()
                .uri(base + "/student-overview")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.gradedStudentCount")
                .isEqualTo("1")
                .jsonPath("$.scoreBins.length()")
                .isEqualTo(20)
                .jsonPath("$.scoreBins[18].count")
                .isEqualTo("1");
        http.get()
                .uri(base + "/students?sort=BEST_SCORE&direction=ASC&limit=1")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].studentId")
                .isEqualTo(ACTOR)
                .jsonPath("$.items[0].latestGradedResult.percentageDisplay")
                .isEqualTo("93.75")
                .jsonPath("$.items[0].correctAnswer")
                .doesNotExist();
        http.get()
                .uri(base + "/students/" + ACTOR + "/attempts")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].attemptId")
                .isEqualTo(attempt.toString());
        http.get()
                .uri(base + "/attempts/" + attempt + "/teacher-review")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.questions[0].correctAnswer.optionLabel")
                .isEqualTo("B")
                .jsonPath("$.sourceSnapshot")
                .doesNotExist()
                .jsonPath("$.result.percentageDisplay")
                .isEqualTo("93.75");
        // A dual-role owner using the Student endpoint must still wait for CLOSED.
        http.get()
                .uri("/api/assessments/attempts/" + attempt + "/review")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("REVIEW_NOT_AVAILABLE");
    }

    @Test
    void studentAdminServiceAnonymousAndForeignTeacherCannotReadTeacherData() {
        for (var roles : List.of(List.of("STUDENT"), List.of("ADMIN"))) {
            http.get()
                    .uri(base + "/student-overview")
                    .headers(h -> h.setBearerAuth(token(ACTOR, "USER", roles)))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
        http.get()
                .uri(base + "/student-overview")
                .headers(h -> h.setBearerAuth(sign(
                        KEY,
                        ISSUER,
                        "service-client",
                        "SERVICE",
                        Map.of("scope", List.of("identity.user-admission")),
                        Instant.now().minusSeconds(5),
                        Instant.now().plusSeconds(300))))
                .exchange()
                .expectStatus()
                .isForbidden();
        http.get().uri(base + "/student-overview").exchange().expectStatus().isUnauthorized();
        http.get()
                .uri(base + "/students")
                .headers(h -> h.setBearerAuth(token(UUID.randomUUID().toString(), "USER", List.of("TEACHER"))))
                .exchange()
                .expectStatus()
                .isNotFound();
        assertEquals(
                "0", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts WHERE attempt_id=?", attempt));
    }

    @Test
    void activeAbandonedForeignBindingsAndPendingPolicyDiscloseNoAnswers() {
        http.get()
                .uri(base + "/attempts/" + attempt + "/teacher-review")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        http.get()
                .uri(base + "/attempts/" + attempt + "/answer-audit")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        http.get()
                .uri(base + "/attempts/" + UUID.randomUUID() + "/teacher-review")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isNotFound();
        submit();
        doThrow(new PublicationFailure(503, "SENSITIVE_POLICY_PENDING"))
                .when(syntheticPolicy)
                .expiresAt(any());
        http.get()
                .uri(base + "/attempts/" + attempt + "/teacher-review")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SENSITIVE_POLICY_PENDING")
                .jsonPath("$.questions")
                .doesNotExist();
        http.post()
                .uri(base + "/attempts/" + attempt + "/answer-observations")
                .headers(h -> h.setBearerAuth(user()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"studentId\":\"forged\",\"value\":\"0001\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit WHERE attempt_id=?", attempt));
    }

    @Test
    void queryDuplicatesSpoofingUnknownMethodsAndAuthorityOutageFailClosed() {
        for (String query : List.of(
                "limit=0",
                "limit=101",
                "limit=1&limit=2",
                "studentId=forged",
                "actorId=forged",
                "sort=SQL",
                "direction=SIDEWAYS",
                "status=ONLINE"))
            http.get()
                    .uri(base + "/students?" + query)
                    .headers(h -> h.setBearerAuth(user()))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        http.get()
                .uri(base + "/students")
                .headers(h -> {
                    h.setBearerAuth(user());
                    h.add("X-User-Id", ACTOR);
                })
                .exchange()
                .expectStatus()
                .isBadRequest();
        http.delete()
                .uri(base + "/students")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
        http.get()
                .uri(base + "/students/extra")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.unavailable()));
        http.get()
                .uri(base + "/student-overview")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503);
    }
}
