package com.quizopia.quiz;

import static com.quizopia.quiz.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.quizopia.quiz.application.QuizVersionQueryService;
import com.quizopia.quiz.security.admission.IdentityAdmissionClient;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

/**
 * ADR-018 FB-015 fencing decision: the internal snapshot endpoint serves only the Assessment
 * source-version handoff for NEW publications (Assessment PublicationService.prepare on
 * create/update/open). Already-delivered publications read Assessment's own stored snapshot and
 * never call this endpoint again, so fencing it to 404 SOURCE_NOT_ACCESSIBLE denies exactly the
 * new-publication references the ADR requires while leaving existing publications untouched.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(QuizDeletionSnapshotHandoffIntegrationTest.Keys.class)
class QuizDeletionSnapshotHandoffIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
        p.add("spring.flyway.enabled", () -> true);
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("quizopia.deletion.enabled", () -> true);
        p.add("quizopia.internal-authority.enabled", () -> true);
        p.add("quizopia.internal-authority.require-tls", () -> false);
        p.add("quizopia.internal-authority.assessment-client-id", () -> "assessment-service");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.quizopia.quiz.application.QuizVersionRepository versions;

    @Autowired
    QuizVersionQueryService versionQueries;

    @Autowired
    com.quizopia.quiz.application.DeletionOperationService deletionOperations;

    @MockitoBean
    IdentityAdmissionClient admission;

    UUID resource, version;
    static final String SOURCE = """
            Câu 1 [NUMERIC_FILL]: Numeric
            Đáp án: 0001
            """;

    @BeforeEach
    void seed() {
        resource = UUID.randomUUID();
        version = UUID.randomUUID();
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
        jdbc.update(
                "insert into quizzes(id,owner_user_id,created_at) values(?,?,now())", resource, UUID.fromString(ACTOR));
        jdbc.update(
                "insert into quiz_drafts(quiz_id,title,authoring_source,updated_at) values(?,?,?,now())",
                resource,
                "Handoff quiz",
                SOURCE);
        var content = new com.quizopia.quiz.domain.markdown.QuizMarkdownParser()
                .parse(SOURCE)
                .content()
                .orElseThrow();
        versions.insert(new com.quizopia.quiz.domain.QuizVersion(
                version,
                resource,
                1,
                "Handoff quiz",
                null,
                SOURCE,
                content,
                1,
                Instant.parse("2026-10-27T00:00:00.123456Z")));
    }

    String path() {
        return "/internal/quiz-versions/" + resource + "/" + version + "/1/snapshot";
    }

    String serviceToken() {
        return sign(
                KEY,
                ISSUER,
                "assessment-service",
                "SERVICE",
                Map.of("scope", java.util.List.of("quiz.version-snapshot.read")),
                Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(300));
    }

    RestClient.ResponseSpec call(String path) {
        return RestClient.create()
                .post()
                .uri("http://127.0.0.1:" + port + path)
                .headers(h -> {
                    h.setBearerAuth(serviceToken());
                    h.set("X-Quizopia-User-Evidence", user());
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve();
    }

    @Test
    void unfencedQuizStillServesNewPublicationSourceHandoff() {
        String body = call(path()).body(String.class);
        assertNotNull(body);
    }

    @Test
    void fencedQuizDeniesNewPublicationSourceHandoffWhileExistingDeliveriesKeepAssessmentStoredSnapshot() {
        assertEquals(200, call(path()).toEntity(String.class).getStatusCode().value());

        UUID operationId = deletionOperations
                .requestDeletion(UUID.fromString(ACTOR), resource, "handoff-key-000000000001", "Handoff quiz")
                .operationId();
        assertEquals(
                "QUIZ_FENCED",
                jdbc.queryForObject(
                        "select status from quiz_deletion_operations where id = ?", String.class, operationId));

        RestClient.ResponseSpec denied = call(path());
        try {
            denied.body(String.class);
            throw new AssertionError("Fenced snapshot handoff must be denied");
        } catch (org.springframework.web.client.RestClientResponseException failure) {
            assertEquals(404, failure.getStatusCode().value());
            assertEquals("{\"code\":\"SOURCE_NOT_ACCESSIBLE\"}", failure.getResponseBodyAsString());
        }

        var stillReadable = versions.findByQuizIdAndVersionNumber(resource, 1).orElseThrow();
        assertEquals(
                stillReadable.id(),
                versionQueries.getOwned(UUID.fromString(ACTOR), resource, 1).id());
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
