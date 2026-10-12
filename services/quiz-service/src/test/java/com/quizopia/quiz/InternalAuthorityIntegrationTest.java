package com.quizopia.quiz;

import static com.quizopia.quiz.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.quizopia.quiz.security.admission.*;
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
@ActiveProfiles("test")
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

    @Autowired
    com.quizopia.quiz.application.QuizVersionRepository versions;

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
                "insert into quizzes(id,owner_user_id,created_at) values(?,?,now())", resource, UUID.fromString(ACTOR));
        var content = new com.quizopia.quiz.domain.markdown.QuizMarkdownParser()
                .parse(SOURCE)
                .content()
                .orElseThrow();
        versions.insert(new com.quizopia.quiz.domain.QuizVersion(
                version,
                resource,
                1,
                "Immutable",
                "description",
                SOURCE,
                content,
                1,
                Instant.parse("2026-10-11T00:00:00.123456Z")));
    }

    String path() {
        return "/internal/quiz-versions/" + resource + "/" + version + "/1/snapshot";
    }

    String scope() {
        return "quiz.version-snapshot.read";
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
        assertEquals(
                "0001",
                json.path("structuredContent")
                        .path("questions")
                        .get(3)
                        .path("numericAnswer")
                        .asString());
        assertEquals(4, json.path("structuredContent").path("questions").size());
        assertEquals(
                mapper.readTree(mapper.writeValueAsString(versions.findByQuizIdAndVersionNumber(resource, 1)
                        .orElseThrow()
                        .structuredContent())),
                json.path("structuredContent"));
        assertEquals(1, json.path("schemaVersion").asInt());
        assertEquals(1, json.path("contentSchemaVersion").asInt());
        for (var o : json.path("structuredContent").path("questions").get(2).path("options"))
            assertFalse(o.path("correct").asBoolean());
        assertEquals(SOURCE, json.path("sourceSnapshot").asString());
        assertEquals(version.toString(), json.path("quizVersionId").asString());
        assertEquals("2026-10-11T00:00:00.123456Z", json.path("createdAt").asString());
        jdbc.update("update quiz_drafts set title='Changed' where quiz_id=?", resource);
        assertEquals(
                SOURCE,
                mapper.readTree(call(path(), service("assessment-service", scope()), user())
                                .getBody())
                        .path("sourceSnapshot")
                        .asString());
    }

    @Autowired
    com.quizopia.quiz.application.QuizApplicationService authoring;

    @Test
    void validPublishedVersionCanExceedTemporaryAssessmentTransportCap() {
        String large = SOURCE.replace("**Stem**", "**Stem** " + "Tiếng Việt ".repeat(90000));
        var draft = authoring.create(
                UUID.fromString(ACTOR), new com.quizopia.quiz.application.QuizDraftInput("Large", null, large));
        var published = authoring
                .publishOwnedDraft(UUID.fromString(ACTOR), draft.quiz().id())
                .version();
        String path = "/internal/quiz-versions/" + published.quizId() + "/" + published.id() + "/1/snapshot";
        var response = call(path, service("assessment-service", scope()), user());
        assertEquals(200, response.getStatusCode().value());
        int bytes = response.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        System.out.println("LD78 published Quiz snapshot bytes=" + bytes);
        assertTrue(bytes > 2097152, "Valid source must demonstrate the temporary compatibility gap");
        var payload = mapper.readTree(response.getBody());
        assertEquals(large, payload.path("sourceSnapshot").asString());
        assertEquals(
                published.structuredContent(),
                versions.findByQuizIdAndVersionNumber(published.quizId(), 1)
                        .orElseThrow()
                        .structuredContent());
        assertEquals(
                large,
                versions.findByQuizIdAndVersionNumber(published.quizId(), 1)
                        .orElseThrow()
                        .sourceSnapshot());
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
    void identityTripleMismatchDenies() {
        assertEquals(
                404,
                call(
                                path().replace(
                                                version.toString(),
                                                UUID.randomUUID().toString()),
                                service("assessment-service", scope()),
                                user())
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
