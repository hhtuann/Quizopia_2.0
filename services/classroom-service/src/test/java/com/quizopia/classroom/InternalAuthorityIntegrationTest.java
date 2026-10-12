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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

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
