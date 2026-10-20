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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;

/** Real PostgreSQL and actual HTTP; frozen W4-B2 authority contract with explicit remote doubles. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.assessment.realtime-authority.enabled=true",
            "quizopia.assessment.realtime-authority.gateway-client-id=gateway-realtime",
            "quizopia.assessment.realtime-authority.allow-insecure-loopback=true"
        })
@Import(ClosedBusinessBoundaryTest.Keys.class)
class RealtimeAuthorityIntegrationTest {
    private static final String PATH = "/internal/realtime/subscription-authorizations";
    private static final String SCOPE = "assessment.realtime.subscription-authorize";
    private static final String AUDIENCE = "quizopia-assessment-realtime-authority";

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

    String studentUser(UUID actor) {
        return sign(
                KEY,
                ISSUER,
                actor.toString(),
                "USER",
                Map.of("roles", List.of("STUDENT")),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    String service(String client, String scope, String audience) {
        var fields = new HashMap<String, Object>();
        fields.put("scope", List.of(scope));
        if (audience != null) fields.put("aud", List.of(audience));
        return sign(
                KEY,
                ISSUER,
                client,
                "SERVICE",
                fields,
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    String service() {
        return service("gateway-realtime", SCOPE, AUDIENCE);
    }

    record Seeded(UUID attemptId, UUID publicationId) {}

    Seeded seed(PublicationConfiguration.Audience audience, int duration, int ageSeconds, boolean open) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var config = new PublicationConfiguration(
                "Realtime authority fixture",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                audience,
                audience == PublicationConfiguration.Audience.CLASS ? classroom : null,
                now.minusSeconds(86400),
                now.plusSeconds(86400),
                duration,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                1);
        var p = publications.create(teacher, UUID.randomUUID().toString(), config);
        if (open)
            publications.open(
                    teacher,
                    p.id(),
                    0,
                    new String(
                            SnapshotFixtures.snapshot(config, "private source marker"),
                            java.nio.charset.StandardCharsets.UTF_8));
        if (!open) return new Seeded(null, p.id());
        UUID attempt = UUID.randomUUID();
        Instant start = now.minusSeconds(ageSeconds);
        db.update(
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,1,?,1,1,'ACTIVE',?,?,?)",
                attempt,
                p.id(),
                student,
                p.id(),
                duration,
                Timestamp.from(start),
                duration == 0 ? null : Timestamp.from(start.plusSeconds(duration * 60L)));
        return new Seeded(attempt, p.id());
    }

    Seeded activeClass() {
        return seed(PublicationConfiguration.Audience.CLASS, 30, 1, true);
    }

    WebTestClient.RequestBodySpec request(String bearer, String evidence) {
        var spec = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .post()
                .uri(PATH)
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json");
        if (evidence != null) spec.header(IdentityAdmissionClient.USER_HEADER, evidence);
        return spec;
    }

    WebTestClient.ResponseSpec attempt(UUID id, UUID actor, String bearer, String evidence) {
        return request(bearer, evidence)
                .bodyValue("{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"" + id + "\"}")
                .exchange();
    }

    WebTestClient.ResponseSpec attemptOwner(UUID id, UUID actor) {
        return attempt(id, actor, service(), user(actor, "STUDENT"));
    }

    WebTestClient.ResponseSpec publication(UUID id, UUID actor, String bearer, String evidence) {
        return request(bearer, evidence)
                .bodyValue("{\"resourceKind\":\"PUBLICATION_TEACHER\",\"resourceId\":\"" + id + "\"}")
                .exchange();
    }

    WebTestClient.ResponseSpec publicationTeacher(UUID id, UUID actor) {
        return publication(id, actor, service(), user(actor, "TEACHER"));
    }

    @Test
    void attemptOwnerPositiveAuthorizationMatchesFrozenResponseShape() {
        var seeded = activeClass();
        var body = attemptOwner(seeded.attemptId(), student)
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(body);
        assertEquals(Set.of("resourceKind", "resourceId", "authorized", "checkedAt"), body.keySet());
        assertEquals("ATTEMPT_OWNER", body.get("resourceKind"));
        assertEquals(seeded.attemptId().toString(), body.get("resourceId"));
        assertEquals(true, body.get("authorized"));
        verify(sources).member(eq(classroom), eq(student), anyString());
        verify(admission).admit(any(), eq("STUDENT"));
    }

    @Test
    void publicationTeacherPositiveAuthorizationRequiresTeacherRoleAndAuthority() {
        var seeded = activeClass();
        publicationTeacher(seeded.publicationId(), teacher)
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.resourceKind")
                .isEqualTo("PUBLICATION_TEACHER")
                .jsonPath("$.resourceId")
                .isEqualTo(seeded.publicationId().toString())
                .jsonPath("$.authorized")
                .isEqualTo(true);
        verify(sources).teacher(eq(classroom), eq(teacher), anyString());
        verify(admission).admit(any(), eq("TEACHER"));
    }

    @Test
    void publicPublicationAttemptSkipsClassroomButKeepsOwnership() {
        var seeded = seed(PublicationConfiguration.Audience.PUBLIC, 30, 1, true);
        attemptOwner(seeded.attemptId(), student)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.authorized")
                .isEqualTo(true);
        verifyNoInteractions(sources);
    }

    @Test
    void missingForeignTerminalExpiredAndDraftResourcesAreMasked() {
        var seeded = activeClass();
        attemptOwner(UUID.randomUUID(), student)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NOT_ACCESSIBLE");
        attemptOwner(seeded.attemptId(), UUID.randomUUID())
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NOT_ACCESSIBLE");
        var expired = seed(PublicationConfiguration.Audience.CLASS, 5, 400, true);
        attemptOwner(expired.attemptId(), student).expectStatus().isNotFound();
        finalizer.submit(new AttemptFinalization.Manual(
                new AttemptFinalization.SelectedAttempt(seeded.attemptId(), student),
                0,
                JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                null));
        attemptOwner(seeded.attemptId(), student).expectStatus().isNotFound();
        var draft = seed(PublicationConfiguration.Audience.CLASS, 30, 1, false);
        publicationTeacher(draft.publicationId(), teacher).expectStatus().isNotFound();
        publicationTeacher(UUID.randomUUID(), teacher).expectStatus().isNotFound();
        publicationTeacher(seeded.publicationId(), UUID.randomUUID())
                .expectStatus()
                .isNotFound();
        verifyNoInteractions(sources);
    }

    @Test
    void insufficientGlobalRoleIsForbiddenBeforeAnyResourceKnowledge() {
        var seeded = activeClass();
        publication(seeded.publicationId(), student, service(), studentUser(student))
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_CALLER_FORBIDDEN");
        // A TEACHER-bearing STUDENT passes the ATTEMPT_OWNER role gate; ownership still masks.
        attempt(seeded.attemptId(), student, service(), user(teacher, "TEACHER"))
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NOT_ACCESSIBLE");
        verify(sources, never()).teacher(any(), any(), any());
        verify(sources, never()).member(any(), any(), any());
    }

    @Test
    void serviceCallerSubjectScopeAndAudienceArePinned() {
        var seeded = activeClass();
        for (String bearer : List.of(
                service("gateway-other", SCOPE, AUDIENCE),
                service("gateway-realtime", "assessment.other", AUDIENCE),
                service("gateway-realtime", SCOPE, null),
                service("gateway-realtime", SCOPE, "quizopia-other-authority"),
                user(student, "STUDENT")))
            attempt(seeded.attemptId(), student, bearer, user(student, "STUDENT"))
                    .expectStatus()
                    .isForbidden()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("SERVICE_CALLER_FORBIDDEN");
        String expiredService = sign(
                KEY,
                ISSUER,
                "gateway-realtime",
                "SERVICE",
                Map.of("scope", List.of(SCOPE), "aud", List.of(AUDIENCE)),
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(300));
        attempt(seeded.attemptId(), student, expiredService, user(student, "STUDENT"))
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_AUTHENTICATION_INVALID");
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .post()
                .uri(PATH)
                .header("Content-Type", "application/json")
                .bodyValue("{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"" + seeded.attemptId() + "\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_AUTHENTICATION_INVALID");
        String forged = sign(
                AdmissionTestTokens.key(),
                ISSUER,
                "gateway-realtime",
                "SERVICE",
                Map.of("scope", List.of(SCOPE), "aud", List.of(AUDIENCE)),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
        attempt(seeded.attemptId(), student, forged, user(student, "STUDENT"))
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_AUTHENTICATION_INVALID");
        verifyNoInteractions(sources);
    }

    @Test
    void userEvidenceIsIndependentlyValidated() {
        var seeded = activeClass();
        var now = Instant.now();
        var fields = Map.<String, Object>of("roles", List.of("STUDENT"));
        for (String evidence : List.of(
                sign(
                        KEY,
                        ISSUER,
                        student.toString(),
                        "SERVICE",
                        Map.of("scope", List.of(SCOPE)),
                        now.minusSeconds(5),
                        now.plusSeconds(300)),
                sign(
                        KEY,
                        "https://wrong-issuer.test",
                        student.toString(),
                        "USER",
                        fields,
                        now.minusSeconds(5),
                        now.plusSeconds(300)),
                sign(KEY, ISSUER, student.toString(), "USER", fields, now.minusSeconds(60), now.minusSeconds(1)),
                sign(
                        AdmissionTestTokens.key(),
                        ISSUER,
                        student.toString(),
                        "USER",
                        fields,
                        now.minusSeconds(5),
                        now.plusSeconds(300)),
                "header." + "x".repeat(90) + ".signature"))
            attempt(seeded.attemptId(), student, service(), evidence)
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("USER_EVIDENCE_INVALID");
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.invalidUser()));
        attemptOwner(seeded.attemptId(), student)
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("USER_EVIDENCE_INVALID");
        verifyNoInteractions(sources);
    }

    @Test
    void strictRequestShapeAndSpoofingRejectionPrecedeAuthority() {
        var seeded = activeClass();
        String bearer = service();
        String evidence = user(student, "STUDENT");
        for (String body : List.of(
                "{}",
                "null",
                "{\"resourceKind\":\"ATTEMPT_OWNER\"}",
                "{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"not-a-uuid\"}",
                "{\"resourceKind\":\"OTHER\",\"resourceId\":\"" + seeded.attemptId() + "\"}",
                "{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\""
                        + seeded.attemptId().toString().toUpperCase(Locale.ROOT) + "\"}",
                "{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"" + seeded.attemptId() + "\",\"extra\":1}",
                "{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"" + seeded.attemptId() + "\"}{}",
                " ".repeat(257)))
            request(bearer, evidence)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INVALID_REQUEST");
        request(bearer, null)
                .bodyValue("{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"" + seeded.attemptId() + "\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        request(bearer, evidence)
                .header("X-User-Id", student.toString())
                .bodyValue("{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"" + seeded.attemptId() + "\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .post()
                .uri(PATH + "?x=1")
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .header(IdentityAdmissionClient.USER_HEADER, evidence)
                .bodyValue("{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"" + seeded.attemptId() + "\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INVALID_REQUEST");
        request(bearer, evidence)
                .header("Content-Type", "text/plain")
                .bodyValue("{\"resourceKind\":\"ATTEMPT_OWNER\",\"resourceId\":\"" + seeded.attemptId() + "\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        verifyNoInteractions(admission, sources);
    }

    @Test
    void classroomAndAdmissionUncertaintyFailClosed() {
        var seeded = activeClass();
        doThrow(new PublicationFailure(404, "CLASSROOM_NOT_ACCESSIBLE"))
                .when(sources)
                .member(any(), any(), any());
        attemptOwner(seeded.attemptId(), student)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NOT_ACCESSIBLE");
        doThrow(new PublicationFailure(403, "ACCESS_DENIED")).when(sources).member(any(), any(), any());
        attemptOwner(seeded.attemptId(), student)
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_CALLER_FORBIDDEN");
        doThrow(PublicationFailure.dependency()).when(sources).member(any(), any(), any());
        attemptOwner(seeded.attemptId(), student)
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTHORITY_UNAVAILABLE");
        reset(sources);
        doThrow(PublicationFailure.dependency()).when(sources).teacher(any(), any(), any());
        publicationTeacher(seeded.publicationId(), teacher)
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTHORITY_UNAVAILABLE");
        reset(sources);
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.unavailable()));
        attemptOwner(seeded.attemptId(), student)
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTHORITY_UNAVAILABLE");
        verifyNoInteractions(sources);
    }

    @Test
    void terminalizationDuringMembershipCheckCannotAuthorizeStaleAttempt() {
        var seeded = activeClass();
        doAnswer(call -> {
                    finalizer.submit(new AttemptFinalization.Manual(
                            new AttemptFinalization.SelectedAttempt(seeded.attemptId(), student),
                            0,
                            JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                            null));
                    return null;
                })
                .when(sources)
                .member(any(), any(), any());
        attemptOwner(seeded.attemptId(), student)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("NOT_ACCESSIBLE");
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT count(*) FROM grading_results WHERE attempt_id=?", Long.class, seeded.attemptId()));
    }
}
