package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.persistence.*;
import com.quizopia.assessment.security.admission.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
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

/** Real PostgreSQL, actual HTTP and independent RS256 decodes; remote authority outcomes are explicit test doubles. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.assessment.proctoring-authority.enabled=true",
            "quizopia.assessment.proctoring-authority.client-id=proctoring",
            "quizopia.assessment.proctoring-authority.allow-insecure-loopback=true"
        })
@Import(ClosedBusinessBoundaryTest.Keys.class)
class ProctoringAuthorityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", DB::getJdbcUrl);
        values.add("spring.datasource.username", DB::getUsername);
        values.add("spring.datasource.password", DB::getPassword);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate db;

    @Autowired
    PublicationStore publications;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    AttemptFinalization finalizer;

    @MockitoBean
    IdentityAdmissionClient admission;

    @MockitoBean
    PublicationSources sources;

    @MockitoBean
    ProctoringTargetEligibility target;

    UUID student, teacher, classroom;

    @BeforeEach
    void setup() {
        student = UUID.randomUUID();
        teacher = UUID.randomUUID();
        classroom = UUID.randomUUID();
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
    }

    String user(UUID actor, String role) {
        return sign(
                KEY,
                ISSUER,
                actor.toString(),
                "USER",
                Map.of("roles", List.of("STUDENT", role).stream().distinct().toList()),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    String service(String client, String scope) {
        return sign(
                KEY,
                ISSUER,
                client,
                "SERVICE",
                Map.of("scope", List.of(scope)),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    String service() {
        return service("proctoring", "assessment.proctoring-authority.read");
    }

    UUID seed(
            PublicationConfiguration.Audience audience,
            PublicationConfiguration.MonitoringChoice monitoring,
            int duration,
            int ageSeconds) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var config = new PublicationConfiguration(
                "Synthetic monitored quiz",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                audience,
                audience == PublicationConfiguration.Audience.CLASS ? classroom : null,
                now.minusSeconds(86400),
                now.plusSeconds(86400),
                duration,
                monitoring,
                1);
        var p = publications.create(teacher, UUID.randomUUID().toString(), config);
        publications.open(
                teacher,
                p.id(),
                0,
                new String(
                        SnapshotFixtures.snapshot(config, "private source marker"),
                        java.nio.charset.StandardCharsets.UTF_8));
        UUID id = UUID.randomUUID();
        Instant start = now.minusSeconds(ageSeconds);
        db.update(
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,1,?,1,1,'ACTIVE',?,?,?)",
                id,
                p.id(),
                student,
                p.id(),
                duration,
                Timestamp.from(start),
                duration == 0 ? null : Timestamp.from(start.plusSeconds(duration * 60L)));
        return id;
    }

    UUID active() {
        return seed(PublicationConfiguration.Audience.CLASS, PublicationConfiguration.MonitoringChoice.ENABLED, 5, 1);
    }

    WebTestClient.RequestBodySpec request(UUID id, String bearer, String evidence) {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .post()
                .uri("/internal/proctoring/attempts/" + id + "/authority")
                .header("Authorization", "Bearer " + bearer)
                .header(IdentityAdmissionClient.USER_HEADER, evidence)
                .header("Content-Type", "application/json");
    }

    WebTestClient.ResponseSpec read(UUID id, UUID actor, String role) {
        return request(id, service(), user(actor, role))
                .bodyValue("{\"viewerRole\":\"" + role + "\",\"purpose\":\"HISTORY\"}")
                .exchange();
    }

    WebTestClient.ResponseSpec live(UUID id, UUID actor, String role) {
        return request(id, service(), user(actor, role))
                .bodyValue("{\"viewerRole\":\"" + role + "\",\"purpose\":\"LIVE_MEDIA\"}")
                .exchange();
    }

    @Test
    void owningTeacherLiveAuthorityRequiresSeparatelyBoundTargetEligibility() {
        UUID id = active();
        live(id, teacher, "TEACHER")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.active")
                .isEqualTo(true)
                .jsonPath("$.purpose")
                .isEqualTo("LIVE_MEDIA");
        Instant started = db.queryForObject(
                "SELECT started_at FROM attempts WHERE id=?",
                (r, n) -> r.getTimestamp(1).toInstant(),
                id);
        verify(target).require(eq(classroom), eq(student), eq(started), anyString());
        verify(sources).teacher(eq(classroom), eq(teacher), anyString());
        verify(sources, never()).member(any(), any(), any());
    }

    @Test
    void targetMembershipOrAccountDenialAndOutageCannotApproveTeacherLiveAuthority() {
        UUID id = active();
        for (int status : List.of(404, 503)) {
            doThrow(new PublicationFailure(status, "AUTHORITY_UNAVAILABLE"))
                    .when(target)
                    .require(any(), any(), any(), anyString());
            live(id, teacher, "TEACHER")
                    .expectStatus()
                    .isEqualTo(status)
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo(status == 404 ? "PROCTORING_NOT_ACCESSIBLE" : "AUTHORITY_UNAVAILABLE");
            assertEquals("ACTIVE", db.queryForObject("SELECT state FROM attempts WHERE id=?", String.class, id));
            assertEquals(
                    0L, db.queryForObject("SELECT count(*) FROM grading_results WHERE attempt_id=?", Long.class, id));
        }
        reset(target);
        doThrow(new AssertionError("History must not consult Student eligibility"))
                .when(target)
                .require(any(), any(), any(), anyString());
        read(id, teacher, "TEACHER")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.purpose")
                .isEqualTo("HISTORY");
        verifyNoInteractions(target);
    }

    @Test
    void teacherLiveTerminalAndExpiredAttemptsDeniedWhileHistoryRemainsReadable() {
        UUID expired = seed(
                PublicationConfiguration.Audience.CLASS, PublicationConfiguration.MonitoringChoice.ENABLED, 5, 400);
        live(expired, teacher, "TEACHER").expectStatus().isNotFound();
        UUID id = active();
        finalizer.submit(new AttemptFinalization.Manual(
                new AttemptFinalization.SelectedAttempt(id, student),
                0,
                JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                null));
        live(id, teacher, "TEACHER").expectStatus().isNotFound();
        read(id, teacher, "TEACHER")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.active")
                .isEqualTo(false);
        verifyNoInteractions(target);
    }

    @Test
    void absentOrInvalidPurposeCannotFallBackToHistoricalOrLivePermission() {
        UUID id = active();
        for (String body : List.of(
                "{\"viewerRole\":\"TEACHER\"}",
                "{\"viewerRole\":\"TEACHER\",\"purpose\":null}",
                "{\"viewerRole\":\"TEACHER\",\"purpose\":\"ANY\"}"))
            request(id, service(), user(teacher, "TEACHER"))
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(admission, sources, target);
    }

    @Test
    void exactMinimalOwnerProjectionAndCurrentTeacherAssignment() {
        UUID id = active();
        var body = read(id, student, "STUDENT")
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(body);
        assertEquals(
                Set.of(
                        "actorId",
                        "viewerRole",
                        "purpose",
                        "attemptId",
                        "publicationId",
                        "classroomId",
                        "studentId",
                        "active",
                        "deadlineAt",
                        "checkedAt",
                        "quizId"),
                body.keySet());
        assertEquals(student.toString(), body.get("actorId"));
        assertEquals(true, body.get("active"));
        assertEquals(classroom.toString(), body.get("classroomId"));
        verify(sources).member(eq(classroom), eq(student), anyString());
        read(id, teacher, "TEACHER").expectStatus().isOk();
        verify(sources).teacher(eq(classroom), eq(teacher), anyString());
        verify(admission, times(2)).admit(any(), any());
    }

    @Test
    void unknownNonownerPublicUnmonitoredAndUnlimitedAreMasked() {
        UUID id = active();
        read(id, UUID.randomUUID(), "STUDENT").expectStatus().isNotFound();
        read(id, UUID.randomUUID(), "TEACHER").expectStatus().isNotFound();
        read(UUID.randomUUID(), student, "STUDENT").expectStatus().isNotFound();
        for (UUID denied : List.of(
                seed(
                        PublicationConfiguration.Audience.PUBLIC,
                        PublicationConfiguration.MonitoringChoice.DISABLED,
                        5,
                        1),
                seed(PublicationConfiguration.Audience.CLASS, PublicationConfiguration.MonitoringChoice.DISABLED, 5, 1),
                seed(
                        PublicationConfiguration.Audience.CLASS,
                        PublicationConfiguration.MonitoringChoice.DISABLED,
                        0,
                        1))) read(denied, student, "STUDENT").expectStatus().isNotFound();
        verifyNoInteractions(sources);
    }

    @Test
    void expiredAndSubmittedStateReturnInactiveWithoutReadDrivenFinalization() {
        UUID expired = seed(
                PublicationConfiguration.Audience.CLASS, PublicationConfiguration.MonitoringChoice.ENABLED, 5, 400);
        read(expired, student, "STUDENT")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.active")
                .isEqualTo(false);
        assertEquals("ACTIVE", db.queryForObject("SELECT state FROM attempts WHERE id=?", String.class, expired));
        assertEquals(
                0L, db.queryForObject("SELECT count(*) FROM grading_results WHERE attempt_id=?", Long.class, expired));
        UUID submitted = active();
        finalizer.submit(new AttemptFinalization.Manual(
                new AttemptFinalization.SelectedAttempt(submitted, student),
                0,
                JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                null));
        read(submitted, student, "STUDENT")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.active")
                .isEqualTo(false);
        read(submitted, teacher, "TEACHER")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.active")
                .isEqualTo(false);
        assertEquals(
                1L,
                db.queryForObject("SELECT count(*) FROM grading_results WHERE attempt_id=?", Long.class, submitted));
    }

    @Test
    void classroomRevocationAndAuthorityOutageFailClosed() {
        UUID id = active();
        doThrow(new PublicationFailure(404, "CLASSROOM_NOT_ACCESSIBLE"))
                .when(sources)
                .member(any(), any(), any());
        read(id, student, "STUDENT")
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PROCTORING_NOT_ACCESSIBLE");
        doThrow(PublicationFailure.dependency()).when(sources).teacher(any(), any(), any());
        read(id, teacher, "TEACHER")
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTHORITY_UNAVAILABLE");
        reset(sources);
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.forbidden()));
        read(id, student, "STUDENT").expectStatus().isForbidden();
        verifyNoInteractions(sources);
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.unavailable()));
        read(id, student, "STUDENT").expectStatus().isEqualTo(503);
    }

    @Test
    void serviceCallerScopeUserBearerAndIndependentUserSignatureRejected() {
        UUID id = active();
        String evidence = user(student, "STUDENT");
        for (String bearer : List.of(
                service("proctoring-other", "assessment.proctoring-authority.read"),
                service("proctoring", "assessment.other"),
                evidence))
            request(id, bearer, evidence)
                    .bodyValue("{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        String forged = sign(
                AdmissionTestTokens.key(),
                ISSUER,
                student.toString(),
                "USER",
                Map.of("roles", List.of("STUDENT")),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
        request(id, service(), forged)
                .bodyValue("{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        request(id, service(), service())
                .bodyValue("{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        read(id, student, "TEACHER").expectStatus().isNotFound();
        verifyNoInteractions(sources);
    }

    @Test
    void exactBoundedBodyAndSpoofingRejectionPrecedeAuthority() {
        UUID id = active();
        for (String body : List.of(
                "{}",
                "null",
                "{\"viewerRole\":\"STUDENT\",\"studentId\":\"forged\"}",
                "{\"viewerRole\":\"STUDENT\",\"viewerRole\":\"TEACHER\"}",
                "{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}{}",
                " ".repeat(257)))
            request(id, service(), user(student, "STUDENT"))
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        request(id, service(), user(student, "STUDENT"))
                .header("X-User-Id", student.toString())
                .bodyValue("{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        verifyNoInteractions(admission, sources);
    }

    @Test
    void originalUserExpiryIssuerFutureIssueAndViewerRoleCheckedIndependently() {
        UUID id = active();
        var now = Instant.now();
        var fields = Map.<String, Object>of("roles", List.of("STUDENT"));
        for (String evidence : List.of(
                sign(
                        KEY,
                        "https://wrong-issuer.test",
                        student.toString(),
                        "USER",
                        fields,
                        now.minusSeconds(5),
                        now.plusSeconds(300)),
                sign(KEY, ISSUER, student.toString(), "USER", fields, now.minusSeconds(60), now.minusSeconds(1)),
                sign(KEY, ISSUER, student.toString(), "USER", fields, now.plusSeconds(60), now.plusSeconds(300))))
            request(id, service(), evidence)
                    .bodyValue("{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        request(id, service(), user(student, "STUDENT"))
                .bodyValue("{\"viewerRole\":\"TEACHER\",\"purpose\":\"HISTORY\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
        verifyNoInteractions(admission, sources);
    }

    @Test
    void assessmentTerminalizationDuringMembershipCheckCannotReturnStaleActive() {
        UUID id = active();
        doAnswer(call -> {
                    finalizer.submit(new AttemptFinalization.Manual(
                            new AttemptFinalization.SelectedAttempt(id, student),
                            0,
                            JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                            null));
                    return null;
                })
                .when(sources)
                .member(any(), any(), any());
        read(id, student, "STUDENT")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.active")
                .isEqualTo(false);
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM grading_results WHERE attempt_id=?", Long.class, id));
    }

    @Test
    void privateUserEvidenceInvalidIsDistinctFromServiceAuthenticationFailure() {
        UUID id = active();
        var now = Instant.now();
        String badService = sign(
                AdmissionTestTokens.key(),
                ISSUER,
                "proctoring",
                "SERVICE",
                Map.of("scope", List.of("assessment.proctoring-authority.read")),
                now.minusSeconds(5),
                now.plusSeconds(300));
        request(id, badService, user(student, "STUDENT"))
                .bodyValue("{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UNAUTHENTICATED");
        String badUser = sign(
                AdmissionTestTokens.key(),
                ISSUER,
                student.toString(),
                "USER",
                Map.of("roles", List.of("STUDENT")),
                now.minusSeconds(5),
                now.plusSeconds(300));
        request(id, service(), badUser)
                .bodyValue("{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("USER_EVIDENCE_INVALID");
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.invalidUser()));
        read(id, student, "STUDENT")
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("USER_EVIDENCE_INVALID");
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
        doThrow(new PublicationFailure(401, "UNAUTHENTICATED")).when(sources).member(any(), any(), any());
        read(id, student, "STUDENT")
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("USER_EVIDENCE_INVALID");
    }
}
