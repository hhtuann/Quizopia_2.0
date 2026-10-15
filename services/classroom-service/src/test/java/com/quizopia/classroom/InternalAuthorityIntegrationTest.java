package com.quizopia.classroom;

import static com.quizopia.classroom.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.quizopia.classroom.security.admission.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP/RS256 and PostgreSQL; current Identity admission is an explicit mock dependency. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("persistence-test")
@Import(InternalAuthorityIntegrationTest.Keys.class)
class InternalAuthorityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
        p.add("spring.flyway.enabled", () -> true);
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("quizopia.internal-authority.enabled", () -> true);
        p.add("quizopia.internal-authority.proctoring-membership-enabled", () -> true);
        p.add("quizopia.internal-authority.require-tls", () -> false);
        p.add("quizopia.internal-authority.assessment-client-id", () -> "assessment-service");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    IdentityAdmissionClient admission;

    @MockitoSpyBean
    com.quizopia.classroom.application.ProctoringMembershipAuthority proctoringAuthority;

    @Autowired
    com.quizopia.classroom.security.internal.InternalAuthorityProperties internal;

    @Autowired
    javax.sql.DataSource datasource;

    UUID resource, version;
    static final String SOURCE =
            """
 Câu 1 [SINGLE_CHOICE]: **Stem**
 *A. One
 B. Two
 C. Three
 D. Four
 Lời giải: Explanation
 Câu 2 [MULTIPLE_CHOICE]: Multi
 *A. First
 B. Second
 *C. Third
 D. Fourth
 Câu 3 [TRUE_FALSE_MATRIX]: All false
 A. One
 B. Two
 C. Three
 D. Four
 Câu 4 [NUMERIC_FILL]: Numeric
 Đáp án: 0001
 Lời giải: Keep zeros
 """;

    @BeforeEach
    void seed() {
        resource = UUID.randomUUID();
        version = UUID.randomUUID();
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
        jdbc.update(
                "insert into classrooms(id,owner_teacher_user_id,name,subject,grade) values(?,?,'Class','Math','10')",
                resource,
                UUID.fromString(ACTOR));
        jdbc.update(
                "insert into classroom_memberships(id,classroom_id,user_id) values(?,?,?)",
                UUID.randomUUID(),
                resource,
                UUID.fromString(ACTOR));
    }

    String path() {
        return "/internal/classrooms/" + resource + "/teacher-authority";
    }

    String scope() {
        return "classroom.teacher-authority.read";
    }

    String student() {
        return sign(
                KEY,
                ISSUER,
                ACTOR,
                "USER",
                Map.of("roles", List.of("STUDENT")),
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(300));
    }

    String service(String client, String scope) {
        return sign(
                KEY,
                ISSUER,
                client,
                "SERVICE",
                Map.of("scope", List.of(scope)),
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(300));
    }

    ResponseEntity<String> call(String path, String service, String user) {
        return RestClient.create()
                .post()
                .uri("http://127.0.0.1:" + port + path)
                .headers(h -> {
                    h.setBearerAuth(service);
                    h.set("X-Quizopia-User-Evidence", user);
                })
                .exchange((q, r) -> ResponseEntity.status(r.getStatusCode())
                        .headers(r.getHeaders())
                        .body(r.bodyTo(String.class)));
    }

    @Test
    void correctOwnerGetsExactSnapshotOrAuthority() throws Exception {
        var response = call(path(), service("assessment-service", scope()), user());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var json = mapper.readTree(response.getBody());
        assertEquals(ACTOR, json.path("actorId").asString());
        assertEquals(resource.toString(), json.path("classroomId").asString());
    }

    @Test
    void wrongOwnerCannotEnumerateResource() {
        String other = sign(
                KEY,
                ISSUER,
                UUID.randomUUID().toString(),
                "USER",
                Map.of("roles", List.of("STUDENT", "TEACHER")),
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(300));
        assertEquals(
                404,
                call(path(), service("assessment-service", scope()), other)
                        .getStatusCode()
                        .value());
    }

    @Test
    void wrongCallerScopeOrUserCallerCannotUseInternalApi() {
        assertEquals(
                403,
                call(path(), service("wrong", scope()), user()).getStatusCode().value());
        assertEquals(
                403,
                call(path(), service("assessment-service", "unrelated"), user())
                        .getStatusCode()
                        .value());
        assertEquals(403, call(path(), user(), user()).getStatusCode().value());
        assertEquals(401, call(path(), "malformed", user()).getStatusCode().value());
    }

    @Test
    void independentUserEvidenceValidationAndRoleSeparation() {
        String service = service("assessment-service", scope());
        assertEquals(401, call(path(), service, service).getStatusCode().value());
        assertEquals(400, call(path(), service, "malformed").getStatusCode().value());
        assertEquals(403, call(path(), service, student()).getStatusCode().value());
        for (String token : List.of(
                sign(
                        key(),
                        ISSUER,
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        Instant.now().minusSeconds(2),
                        Instant.now().plusSeconds(300)),
                sign(
                        KEY,
                        "https://wrong",
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        Instant.now().minusSeconds(2),
                        Instant.now().plusSeconds(300)),
                sign(
                        KEY,
                        ISSUER,
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        Instant.now().minusSeconds(300),
                        Instant.now().minusSeconds(120))))
            assertEquals(401, call(path(), service, token).getStatusCode().value());
    }

    @Test
    void admissionFailureCannotReachOwnedSource() {
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.unavailable()));
        assertEquals(
                503,
                call(path(), service("assessment-service", scope()), user())
                        .getStatusCode()
                        .value());
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.invalidUser()));
        assertEquals(
                401,
                call(path(), service("assessment-service", scope()), user())
                        .getStatusCode()
                        .value());
    }

    @Test
    void duplicateEvidenceAndSpoofedActorAreRejected() {
        var response = RestClient.create()
                .post()
                .uri("http://127.0.0.1:" + port + path())
                .headers(h -> {
                    h.setBearerAuth(service("assessment-service", scope()));
                    h.add("X-Quizopia-User-Evidence", user());
                    h.add("X-Quizopia-User-Evidence", user());
                    h.set("X-User-Id", ACTOR);
                })
                .exchange((q, r) -> r.getStatusCode().value());
        assertEquals(400, response);
        assertEquals(
                400,
                call(path() + "?ownerUserId=" + ACTOR, service("assessment-service", scope()), user())
                        .getStatusCode()
                        .value());
        assertEquals(
                403,
                call(path() + "/nested", service("assessment-service", scope()), user())
                        .getStatusCode()
                        .value());
    }

    @Test
    void onlyPersistedMembershipAuthorizesStudent() {
        String path = "/internal/classrooms/" + resource + "/student-membership";
        String service = service("assessment-service", "classroom.membership.read");
        assertEquals(200, call(path, service, student()).getStatusCode().value());
        jdbc.update("delete from classroom_memberships where classroom_id=?", resource);
        jdbc.update(
                "insert into pending_classroom_invitations(id,classroom_id,normalized_email,invited_full_name) values(?,?,'pending@gmail.com','Pending')",
                UUID.randomUUID(),
                resource);
        assertEquals(404, call(path, service, student()).getStatusCode().value());
        assertEquals(
                404,
                call(path.replace(resource.toString(), UUID.randomUUID().toString()), service, student())
                        .getStatusCode()
                        .value());
        assertEquals(
                400,
                call(path + "?studentId=" + ACTOR, service, student())
                        .getStatusCode()
                        .value());
    }

    String proctoring(UUID target) {
        return "/internal/classrooms/" + resource + "/proctoring-students/" + target + "/membership";
    }

    String proctoringService() {
        return service("assessment-service", "classroom.proctoring-membership.read");
    }

    UUID targetMember() {
        UUID target = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES (?,?,?)",
                UUID.randomUUID(),
                resource,
                target);
        return target;
    }

    @Test
    void owningTeacherGetsOnlyActualTargetMembershipAndPrimaryTime() throws Exception {
        var target = targetMember();
        var before = Instant.now().minusSeconds(1);
        var response = call(proctoring(target), proctoringService(), user());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var body = mapper.readTree(response.getBody());
        assertEquals(Set.of("classroomId", "studentId", "checkedAt"), new HashSet<>(body.propertyNames()));
        assertEquals(resource.toString(), body.path("classroomId").asString());
        assertEquals(target.toString(), body.path("studentId").asString());
        var checked = Instant.parse(body.path("checkedAt").asString());
        assertTrue(checked.isAfter(before));
        assertFalse(checked.isAfter(Instant.now()));
        verify(admission).admit(any(), eq("TEACHER"));
    }

    @Test
    void targetRemovalCannotBeSubstitutedByTeacherMembershipAndRecoveryIsFresh() {
        var target = targetMember();
        assertEquals(
                200,
                call(proctoring(target), proctoringService(), user())
                        .getStatusCode()
                        .value());
        jdbc.update("DELETE FROM classroom_memberships WHERE classroom_id=? AND user_id=?", resource, target);
        jdbc.update(
                "INSERT INTO pending_classroom_invitations(id,classroom_id,normalized_email,invited_full_name) VALUES (?,?,?,?)",
                UUID.randomUUID(),
                resource,
                "target@gmail.com",
                "Synthetic invitation");
        var absent = call(proctoring(target), proctoringService(), user());
        assertEquals(404, absent.getStatusCode().value());
        assertEquals("{\"code\":\"CLASSROOM_NOT_ACCESSIBLE\"}", absent.getBody());
        jdbc.update(
                "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES (?,?,?)",
                UUID.randomUUID(),
                resource,
                target);
        assertEquals(
                200,
                call(proctoring(target), proctoringService(), user())
                        .getStatusCode()
                        .value());
    }

    @Test
    void currentOwningTeacherAndTargetClassroomBindingAreRequired() {
        var target = targetMember();
        String peer = sign(
                KEY,
                ISSUER,
                UUID.randomUUID().toString(),
                "USER",
                Map.of("roles", List.of("STUDENT", "TEACHER")),
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(300));
        assertEquals(
                404,
                call(proctoring(target), proctoringService(), peer)
                        .getStatusCode()
                        .value());
        assertEquals(
                404,
                call(proctoring(UUID.randomUUID()), proctoringService(), user())
                        .getStatusCode()
                        .value());
        assertEquals(
                404,
                call(
                                proctoring(target)
                                        .replace(
                                                resource.toString(),
                                                UUID.randomUUID().toString()),
                                proctoringService(),
                                user())
                        .getStatusCode()
                        .value());
        jdbc.update("UPDATE classrooms SET owner_teacher_user_id=? WHERE id=?", UUID.randomUUID(), resource);
        assertEquals(
                404,
                call(proctoring(target), proctoringService(), user())
                        .getStatusCode()
                        .value());
    }

    @Test
    void newScopeCannotBeReplacedByExistingAuthorityScopesOrUserCaller() {
        var target = targetMember();
        for (String caller : List.of(
                user(),
                service("wrong-assessment", "classroom.proctoring-membership.read"),
                service("assessment-service", "classroom.membership.read"),
                service("assessment-service", "classroom.teacher-authority.read"))) {
            assertEquals(
                    403,
                    call(proctoring(target), caller, user()).getStatusCode().value());
        }
        assertEquals(
                401,
                call(proctoring(target), "malformed", user()).getStatusCode().value());
    }

    @Test
    void originalTeacherEvidenceIsIndependentlyVerifiedWithoutStudentImpersonation() {
        var target = targetMember();
        assertEquals(
                403,
                call(proctoring(target), proctoringService(), student())
                        .getStatusCode()
                        .value());
        assertEquals(
                401,
                call(proctoring(target), proctoringService(), proctoringService())
                        .getStatusCode()
                        .value());
        for (String evidence : List.of(
                sign(
                        key(),
                        ISSUER,
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        Instant.now().minusSeconds(2),
                        Instant.now().plusSeconds(300)),
                sign(
                        KEY,
                        "https://wrong",
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        Instant.now().minusSeconds(2),
                        Instant.now().plusSeconds(300)),
                sign(
                        KEY,
                        ISSUER,
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        Instant.now().minusSeconds(300),
                        Instant.now().minusSeconds(120)))) {
            assertEquals(
                    401,
                    call(proctoring(target), proctoringService(), evidence)
                            .getStatusCode()
                            .value());
        }
    }

    @Test
    void newAuthorityRejectsQueryBodyCookieAndSpoofedActor() {
        var target = targetMember();
        assertEquals(
                400,
                call(proctoring(target) + "?studentId=" + ACTOR, proctoringService(), user())
                        .getStatusCode()
                        .value());
        for (String header : List.of("Cookie", "X-User-Id", "X-Quizopia-Student-Evidence")) {
            var response = RestClient.create()
                    .post()
                    .uri("http://127.0.0.1:" + port + proctoring(target))
                    .headers(h -> {
                        h.setBearerAuth(proctoringService());
                        h.set("X-Quizopia-User-Evidence", user());
                        h.set(header, ACTOR);
                    })
                    .exchange((q, r) -> r.getStatusCode().value());
            assertEquals(400, response);
        }
        int response = RestClient.create()
                .post()
                .uri("http://127.0.0.1:" + port + proctoring(target))
                .headers(h -> {
                    h.setBearerAuth(proctoringService());
                    h.set("X-Quizopia-User-Evidence", user());
                })
                .body("{}")
                .exchange((q, r) -> r.getStatusCode().value());
        assertEquals(400, response);
        assertEquals(
                403,
                call(proctoring(target) + "/nested", proctoringService(), user())
                        .getStatusCode()
                        .value());
        assertEquals(
                403,
                call(
                                proctoring(target)
                                        .replace(
                                                target.toString(),
                                                target.toString().toUpperCase(Locale.ROOT)),
                                proctoringService(),
                                user())
                        .getStatusCode()
                        .value());
    }

    @Test
    void newAuthorityCurrentAdmissionFailureDoesNotBecomeMembershipSuccessOrNotFound() {
        var unknownTarget = UUID.randomUUID();
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.unavailable()));
        assertEquals(
                503,
                call(proctoring(unknownTarget), proctoringService(), user())
                        .getStatusCode()
                        .value());
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.invalidUser()));
        assertEquals(
                401,
                call(proctoring(unknownTarget), proctoringService(), user())
                        .getStatusCode()
                        .value());
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.forbidden()));
        assertEquals(
                403,
                call(proctoring(unknownTarget), proctoringService(), user())
                        .getStatusCode()
                        .value());
    }

    @Test
    void separateFeatureIsDefaultOffAndDisabledHttpGateDoesNotUseAdmission() {
        assertFalse(new com.quizopia.classroom.security.internal.InternalAuthorityProperties()
                .isProctoringMembershipEnabled());
        internal.setProctoringMembershipEnabled(false);
        try {
            assertEquals(
                    503,
                    call(proctoring(targetMember()), proctoringService(), user())
                            .getStatusCode()
                            .value());
            verifyNoInteractions(admission);
        } finally {
            internal.setProctoringMembershipEnabled(true);
        }
    }

    @Test
    void lockedMembershipSourceReturnsSanitizedUnavailableAndRecoversWithoutMutation() throws Exception {
        var target = targetMember();
        try (var connection = datasource.getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("LOCK TABLE classroom_memberships IN ACCESS EXCLUSIVE MODE");
            var response = call(proctoring(target), proctoringService(), user());
            assertEquals(503, response.getStatusCode().value());
            assertEquals("{\"code\":\"AUTHORITY_UNAVAILABLE\"}", response.getBody());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            connection.rollback();
        }
        assertEquals(
                200,
                call(proctoring(target), proctoringService(), user())
                        .getStatusCode()
                        .value());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM classroom_memberships WHERE classroom_id=? AND user_id=?",
                        Integer.class,
                        resource,
                        target));
    }

    void delayAfterActualMembershipQuery() {
        doAnswer(invocation -> {
                    var result = invocation.callRealMethod();
                    Thread.sleep(4000);
                    return result;
                })
                .when(proctoringAuthority)
                .confirm(any(), any(), any());
    }

    @Test
    void originalTeacherExpiryAfterDatabaseCheckPreventsPositiveResponse() {
        var target = targetMember();
        delayAfterActualMembershipQuery();
        String teacher = sign(
                KEY,
                ISSUER,
                ACTOR,
                "USER",
                Map.of("roles", List.of("STUDENT", "TEACHER")),
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(3));
        var response = call(proctoring(target), proctoringService(), teacher);
        assertEquals(401, response.getStatusCode().value());
        assertEquals("{\"code\":\"USER_EVIDENCE_INVALID\"}", response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(proctoringAuthority).confirm(UUID.fromString(ACTOR), resource, target);
    }

    @Test
    void serviceExpiryAfterDatabaseCheckPreventsPositiveResponse() {
        var target = targetMember();
        delayAfterActualMembershipQuery();
        String service = sign(
                KEY,
                ISSUER,
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of("classroom.proctoring-membership.read")),
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(3));
        var response = call(proctoring(target), service, user());
        assertEquals(401, response.getStatusCode().value());
        assertEquals("{\"code\":\"SERVICE_AUTHENTICATION_INVALID\"}", response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(proctoringAuthority).confirm(UUID.fromString(ACTOR), resource, target);
    }

    @TestConfiguration
    static class Keys {
        @Bean
        @Primary
        JwtDecoder decoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
