package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.persistence.*;
import com.quizopia.assessment.security.admission.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;

/** Actual demo providers + restricted runtime DB role; only remote Identity/CLASS are test stubs. */
@Testcontainers
@ActiveProfiles("academic-demo")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.publication.enabled=true",
            "logging.level.org.springframework.test.web.reactive.server.ExchangeResult=OFF",
            "quizopia.assessment.features.results-enabled=true",
            "quizopia.assessment.features.attempts-enabled=true",
            "quizopia.assessment.demo.synthetic-only=true",
            "quizopia.assessment.demo.current-key=fixture",
            "quizopia.assessment.demo.keys.fixture=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "quizopia.assessment.demo.custodian-user=quizopia_demo_custodian",
            "quizopia.assessment.demo.custodian-password=fixture-only",
            "quizopia.assessment.closure.cursor-keys.v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "quizopia.assessment.demo.publish-millis=3600000",
            "quizopia.assessment.demo.cleanup-millis=3600000"
        })
@Import(ClosedBusinessBoundaryTest.Keys.class)
class AcademicDemoHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_assessment_roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) throws Exception {
        try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
                var s = c.createStatement()) {
            s.execute("CREATE ROLE quizopia_demo_custodian LOGIN PASSWORD 'fixture-only' NOINHERIT");
            s.execute("GRANT quizopia_assessment_custodian TO quizopia_demo_custodian");
            s.execute("ALTER ROLE quizopia_demo_custodian SET role TO quizopia_assessment_custodian");
            s.execute("GRANT USAGE ON SCHEMA public TO quizopia_assessment_custodian,assessment");
        }
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", () -> "assessment");
        p.add("spring.datasource.password", () -> "assessment_local_only");
        p.add("spring.flyway.user", DB::getUsername);
        p.add("spring.flyway.password", DB::getPassword);
    }

    @Autowired
    JdbcTemplate db;

    @Autowired
    com.quizopia.assessment.audit.AcademicRetentionWorker retention;

    @Value("${local.server.port}")
    int port;

    @MockitoBean
    IdentityAdmissionClient admission;

    @MockitoBean
    PublicationSources sources;

    AtomicFinalizationIntegrationTest f;
    UUID publication, attempt;
    String base;
    WebTestClient http;
    JdbcTemplate admin;

    @BeforeEach
    void seed() {
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
        admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        f = new AtomicFinalizationIntegrationTest();
        f.db = admin;
        f.manager = new org.springframework.jdbc.support.JdbcTransactionManager(admin.getDataSource());
        f.core = new JdbcAttemptFinalization(
                db, new org.springframework.jdbc.support.JdbcTransactionManager(db.getDataSource()));
        f.student = UUID.fromString(ACTOR);
        var p = f.publication(0);
        publication = p.publication();
        // Use the Publication's actual synthetic owner for Teacher tests, never bypass ownership.
        UUID owner = UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", publication));
        admin.update(
                "INSERT INTO assessment_demo_subjects VALUES(?,'FICTIONAL_ACADEMIC_DEMO_V1'),(?,'FICTIONAL_ACADEMIC_DEMO_V1') ON CONFLICT DO NOTHING",
                owner,
                f.student);
        attempt = f.active(p, 0);
        base = "/api/assessments/publications/" + publication;
        http = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    String teacher() {
        return sign(
                KEY,
                ISSUER,
                f.scalar("SELECT owner_id FROM publications WHERE id=?", publication),
                "USER",
                Map.of("roles", List.of("STUDENT", "TEACHER")),
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(300));
    }

    @Test
    void actualDemoPolicyAllowsOnlySubmittedOwnerReadAndCommitsReceipts() {
        http.get()
                .uri(base + "/attempts/" + attempt + "/teacher-review")
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        http.get()
                .uri(base + "/attempts/" + attempt + "/teacher-review")
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.questions[0].correctAnswer.optionLabel")
                .isEqualTo("B");
        http.get()
                .uri(base + "/attempts/" + attempt + "/answer-audit")
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].source")
                .isEqualTo("SERVER_AUTHORITY");
        assertEquals(
                "2", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts WHERE attempt_id=?", attempt));
        http.get()
                .uri("/api/assessments/attempts/" + attempt + "/review")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        http.get()
                .uri(base + "/attempts/" + attempt + "/teacher-review")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void consentWireRejectsForgedDuplicateAndMalformedBodiesWithoutAffectingAcademicWork() {
        String path = base + "/attempts/" + attempt + "/answer-observation-choice";
        http.get()
                .uri(path)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false);
        for (String body : List.of(
                "{",
                "{\"enabled\":true,\"enabled\":false,\"noticeVersion\":\"ACADEMIC_DEMO_V1\"}",
                "{\"enabled\":true,\"noticeVersion\":\"ACADEMIC_DEMO_V1\",\"studentId\":\"forged\"}")) {
            http.put()
                    .uri(path)
                    .headers(h -> h.setBearerAuth(user()))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        }
        http.put()
                .uri(path)
                .headers(h -> h.setBearerAuth(user()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":false,\"noticeVersion\":\"ACADEMIC_DEMO_V1\"}")
                .exchange()
                .expectStatus()
                .isOk();
        f.core.submit(f.manual(attempt, 0, null));
        assertEquals("SUBMITTED", f.scalar("SELECT state FROM attempts WHERE id=?", attempt));
    }

    @Test
    void runtimeCannotDeleteForgeHoldsOrEnrollRealSubjectsAndRevocationStillDenies() {
        assertThrows(RuntimeException.class, () -> db.update("DELETE FROM assessment_answer_audit"));
        assertThrows(
                RuntimeException.class,
                () -> db.update(
                        "INSERT INTO assessment_demo_subjects VALUES(?,'FICTIONAL_ACADEMIC_DEMO_V1')",
                        UUID.randomUUID()));
        assertThrows(
                RuntimeException.class, () -> db.queryForObject("SELECT assessment_expire_audit(1)", Integer.class));
        assertThrows(
                RuntimeException.class,
                () -> db.queryForList("SELECT assessment_review_audit_hold(?)", UUID.randomUUID()));
        f.core.submit(f.manual(attempt, 0, null));
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.unavailable()));
        http.get()
                .uri(base + "/attempts/" + attempt + "/teacher-review")
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.questions")
                .doesNotExist();
    }

    @Test
    void actualCustodianWorkerExpiresOnlyAuditAndReceiptsWithoutTouchingGrades() {
        f.core.submit(f.manual(attempt, 0, null));
        http.get()
                .uri(base + "/attempts/" + attempt + "/teacher-review")
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isOk();
        try {
            admin.execute(
                    "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE AS $$ SELECT clock_timestamp()+interval '181 days' $$");
            retention.cleanup();
            assertEquals(
                    "0",
                    f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts WHERE attempt_id=?", attempt));
            assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit WHERE attempt_id=?", attempt));
            assertEquals("0", f.scalar("SELECT count(*) FROM assessment_audit_pending WHERE attempt_id=?", attempt));
            assertEquals("1", f.scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", attempt));
        } finally {
            admin.execute(
                    "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE AS $$ SELECT clock_timestamp() $$");
        }
    }
}
