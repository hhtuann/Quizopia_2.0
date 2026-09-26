package com.quizopia.quiz;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quizopia.quiz.api.QuizApiExceptionHandler;
import com.quizopia.quiz.api.QuizController;
import com.quizopia.quiz.application.QuizApplicationService;
import com.quizopia.quiz.application.QuizDraftDetails;
import com.quizopia.quiz.application.QuizDraftInput;
import com.quizopia.quiz.application.QuizDraftNotFoundException;
import com.quizopia.quiz.application.QuizNotFoundException;
import com.quizopia.quiz.application.QuizOwnershipDeniedException;
import com.quizopia.quiz.configuration.SecurityConfiguration;
import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDraft;
import com.quizopia.quiz.security.QuizSecurityErrorHandler;
import com.quizopia.quiz.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.quiz.security.QuizopiaTokenClaims;
import com.quizopia.quiz.security.TeacherAuthoringPrincipalResolver;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = QuizApiSecurityIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuizApiSecurityIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-26T01:02:03.123456Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-26T04:05:06.654321Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    QuizApplicationService quizApplicationService;

    @Autowired
    JwtDecoder jwtDecoder;

    @BeforeEach
    void resetMocks() {
        reset(quizApplicationService, jwtDecoder);
    }

    @Test
    void teacherCreatesDraftWithTokenSubjectAsOwnerAndExactOpaqueSource() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        String source = "  opaque source\r\n[not parsed]\t";
        configureUserToken("teacher", ownerUserId, List.of("TEACHER"));
        when(quizApplicationService.create(any(), any()))
                .thenReturn(details(quizId, ownerUserId, "Title", "Description", source, UPDATED_AT));

        mvc.perform(post("/api/quizzes")
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("Title", "Description", source)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/quizzes/" + quizId + "/draft"))
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.title").value("Title"))
                .andExpect(jsonPath("$.description").value("Description"))
                .andExpect(jsonPath("$.authoringSource").value(source))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());

        ArgumentCaptor<UUID> callerCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<QuizDraftInput> inputCaptor = ArgumentCaptor.forClass(QuizDraftInput.class);
        verify(quizApplicationService).create(callerCaptor.capture(), inputCaptor.capture());
        assertEquals(ownerUserId, callerCaptor.getValue());
        assertEquals(new QuizDraftInput("Title", "Description", source), inputCaptor.getValue());
    }

    @Test
    void createRejectsCallerControlledOwnerAndFutureFields() throws Exception {
        configureUserToken("teacher", UUID.randomUUID(), List.of("TEACHER"));
        String request =
                """
                {"title":"Title","description":null,"authoringSource":"opaque","ownerUserId":"%s"}
                """
                        .formatted(UUID.randomUUID());

        mvc.perform(post("/api/quizzes")
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(quizApplicationService, never()).create(any(), any());
    }

    @Test
    void ownerTeacherReadsCurrentDraft() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        configureUserToken("owner", ownerUserId, List.of("STUDENT", "TEACHER"));
        when(quizApplicationService.getOwnedDraft(ownerUserId, quizId))
                .thenReturn(details(quizId, ownerUserId, "Title", null, "opaque", UPDATED_AT));

        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.title").value("Title"))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.authoringSource").value("opaque"));
        verify(quizApplicationService).getOwnedDraft(ownerUserId, quizId);
    }

    @Test
    void readMapsMissingQuizOwnershipDenialAndMissingDraft() throws Exception {
        UUID callerUserId = UUID.randomUUID();
        UUID missingQuizId = UUID.randomUUID();
        UUID deniedQuizId = UUID.randomUUID();
        UUID missingDraftQuizId = UUID.randomUUID();
        configureUserToken("teacher", callerUserId, List.of("TEACHER"));
        when(quizApplicationService.getOwnedDraft(callerUserId, missingQuizId))
                .thenThrow(new QuizNotFoundException(missingQuizId));
        when(quizApplicationService.getOwnedDraft(callerUserId, deniedQuizId))
                .thenThrow(new QuizOwnershipDeniedException(deniedQuizId, callerUserId));
        when(quizApplicationService.getOwnedDraft(callerUserId, missingDraftQuizId))
                .thenThrow(new QuizDraftNotFoundException(missingDraftQuizId));

        mvc.perform(get("/api/quizzes/{quizId}/draft", missingQuizId).header("Authorization", "Bearer teacher"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_NOT_FOUND"));
        mvc.perform(get("/api/quizzes/{quizId}/draft", deniedQuizId).header("Authorization", "Bearer teacher"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(content().string(not(containsString(callerUserId.toString()))));
        mvc.perform(get("/api/quizzes/{quizId}/draft", missingDraftQuizId).header("Authorization", "Bearer teacher"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_DRAFT_NOT_FOUND"));
    }

    @Test
    void ownerTeacherReplacesDraftFieldsWhileStableCreatedAtRemainsUnchanged() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        String changedSource = "\n replacement source [still opaque]\t";
        configureUserToken("teacher", ownerUserId, List.of("TEACHER"));
        when(quizApplicationService.updateOwnedDraft(eq(ownerUserId), eq(quizId), any()))
                .thenReturn(details(quizId, ownerUserId, "Changed", null, changedSource, UPDATED_AT));

        mvc.perform(put("/api/quizzes/{quizId}/draft", quizId)
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("Changed", null, changedSource)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.title").value("Changed"))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.authoringSource").value(changedSource))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()));

        ArgumentCaptor<QuizDraftInput> inputCaptor = ArgumentCaptor.forClass(QuizDraftInput.class);
        verify(quizApplicationService).updateOwnedDraft(eq(ownerUserId), eq(quizId), inputCaptor.capture());
        assertEquals(new QuizDraftInput("Changed", null, changedSource), inputCaptor.getValue());
    }

    @Test
    void updateMapsMissingQuizOwnershipDenialAndMissingDraft() throws Exception {
        UUID callerUserId = UUID.randomUUID();
        UUID missingQuizId = UUID.randomUUID();
        UUID deniedQuizId = UUID.randomUUID();
        UUID missingDraftQuizId = UUID.randomUUID();
        configureUserToken("teacher", callerUserId, List.of("TEACHER"));
        QuizDraftInput input = new QuizDraftInput("Changed", null, "replacement");
        when(quizApplicationService.updateOwnedDraft(callerUserId, missingQuizId, input))
                .thenThrow(new QuizNotFoundException(missingQuizId));
        when(quizApplicationService.updateOwnedDraft(callerUserId, deniedQuizId, input))
                .thenThrow(new QuizOwnershipDeniedException(deniedQuizId, callerUserId));
        when(quizApplicationService.updateOwnedDraft(callerUserId, missingDraftQuizId, input))
                .thenThrow(new QuizDraftNotFoundException(missingDraftQuizId));
        String body = request("Changed", null, "replacement");

        mvc.perform(put("/api/quizzes/{quizId}/draft", missingQuizId)
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_NOT_FOUND"));
        mvc.perform(put("/api/quizzes/{quizId}/draft", deniedQuizId)
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(put("/api/quizzes/{quizId}/draft", missingDraftQuizId)
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_DRAFT_NOT_FOUND"));
    }

    @Test
    void studentAndAdminWithoutTeacherAndServiceAreDeniedAtTheRoute() throws Exception {
        UUID quizId = UUID.randomUUID();
        configureUserToken("student", UUID.randomUUID(), List.of("STUDENT"));
        configureUserToken("admin", UUID.randomUUID(), List.of("ADMIN"));
        configureServiceToken("service");

        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer student"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer service"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verify(quizApplicationService, never()).getOwnedDraft(any(), any());
    }

    @Test
    void explicitTeacherRoleAllowsStudentTeacherAndAdminTeacherUsers() throws Exception {
        UUID quizId = UUID.randomUUID();
        UUID studentTeacher = UUID.randomUUID();
        UUID adminTeacher = UUID.randomUUID();
        configureUserToken("student-teacher", studentTeacher, List.of("STUDENT", "TEACHER"));
        configureUserToken("admin-teacher", adminTeacher, List.of("ADMIN", "TEACHER"));
        when(quizApplicationService.getOwnedDraft(any(), eq(quizId)))
                .thenAnswer(
                        invocation -> details(quizId, invocation.getArgument(0), "Title", null, "opaque", UPDATED_AT));

        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer student-teacher"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer admin-teacher"))
                .andExpect(status().isOk());
        verify(quizApplicationService).getOwnedDraft(studentTeacher, quizId);
        verify(quizApplicationService).getOwnedDraft(adminTeacher, quizId);
    }

    @Test
    void missingCredentialAndMixedJwtReturn401() throws Exception {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("mixed"))
                .thenReturn(jwt(
                        userId.toString(),
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("TEACHER"),
                                QuizopiaTokenClaims.SCOPE,
                                List.of("quiz.write"))));

        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer mixed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(content().string(not(containsString("quiz.write"))))
                .andExpect(content().string(not(containsString(userId.toString()))));
    }

    @Test
    void malformedJsonAndMalformedQuizIdReturnInvalidRequest() throws Exception {
        configureUserToken("teacher", UUID.randomUUID(), List.of("TEACHER"));

        mvc.perform(post("/api/quizzes")
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/quizzes"));
        mvc.perform(get("/api/quizzes/not-a-uuid/draft").header("Authorization", "Bearer teacher"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("IllegalArgumentException"))));
    }

    @Test
    void generatedOpenApiDescribesImplementedOperationsSecurityResponsesAndSchemas() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode document = objectMapper.readTree(body);
        JsonNode paths = document.path("paths");

        assertEquals(Set.of("/api/quizzes", "/api/quizzes/{quizId}/draft"), fieldNames(paths));
        JsonNode create = paths.path("/api/quizzes").path("post");
        JsonNode read = paths.path("/api/quizzes/{quizId}/draft").path("get");
        JsonNode update = paths.path("/api/quizzes/{quizId}/draft").path("put");

        assertFalse(document.has("security"));
        JsonNode securitySchemes = document.path("components").path("securitySchemes");
        assertEquals(Set.of("quizopiaBearerAuth"), fieldNames(securitySchemes));
        JsonNode bearerScheme = securitySchemes.path("quizopiaBearerAuth");
        assertEquals("http", bearerScheme.path("type").asString());
        assertEquals("bearer", bearerScheme.path("scheme").asString());
        assertEquals("JWT", bearerScheme.path("bearerFormat").asString());
        assertOperationRequiresBearer(create);
        assertOperationRequiresBearer(read);
        assertOperationRequiresBearer(update);

        assertFalse(create.isMissingNode());
        assertFalse(paths.path("/api/quizzes").has("get"));
        assertFalse(read.isMissingNode());
        assertFalse(update.isMissingNode());
        assertFalse(paths.path("/api/quizzes/{quizId}/draft").has("delete"));
        assertResponses(create, Set.of("201", "400", "401", "403"), "201");
        assertResponses(read, Set.of("200", "400", "401", "403", "404"), "200");
        assertResponses(update, Set.of("200", "400", "401", "403", "404"), "200");

        JsonNode schemas = document.path("components").path("schemas");
        assertEquals(Set.of("QuizApiError", "QuizDraftRequest", "QuizDraftResponse"), fieldNames(schemas));
        JsonNode requestSchema = schemas.path("QuizDraftRequest");
        assertEquals(Set.of("title", "description", "authoringSource"), fieldNames(requestSchema.path("properties")));
        assertTrue(requestSchema.has("additionalProperties"));
        assertFalse(requestSchema.path("additionalProperties").asBoolean());
        assertNullableString(requestSchema.path("properties").path("title"));
        assertNullableString(requestSchema.path("properties").path("description"));
        assertNullableString(requestSchema.path("properties").path("authoringSource"));

        JsonNode responseSchema = schemas.path("QuizDraftResponse");
        assertEquals(
                Set.of("quizId", "title", "description", "authoringSource", "createdAt", "updatedAt"),
                fieldNames(responseSchema.path("properties")));
        assertEquals(Set.of("quizId", "createdAt", "updatedAt"), textValues(responseSchema.path("required")));
        assertNonNullableString(responseSchema.path("properties").path("quizId"));
        assertNonNullableString(responseSchema.path("properties").path("createdAt"));
        assertNonNullableString(responseSchema.path("properties").path("updatedAt"));
        assertNullableString(responseSchema.path("properties").path("title"));
        assertNullableString(responseSchema.path("properties").path("description"));
        assertNullableString(responseSchema.path("properties").path("authoringSource"));

        assertEquals(
                Set.of("code", "message", "status", "path"),
                fieldNames(schemas.path("QuizApiError").path("properties")));
    }

    private static void assertOperationRequiresBearer(JsonNode operation) {
        JsonNode security = operation.path("security");
        assertTrue(security.isArray());
        assertEquals(1, security.size());
        JsonNode requirement = security.get(0).path("quizopiaBearerAuth");
        assertTrue(requirement.isArray());
        assertEquals(0, requirement.size());
    }

    private static void assertResponses(JsonNode operation, Set<String> expectedCodes, String successCode) {
        JsonNode responses = operation.path("responses");
        assertEquals(expectedCodes, fieldNames(responses));
        assertResponseSchema(responses.path(successCode), "QuizDraftResponse");
        expectedCodes.stream()
                .filter(code -> !code.equals(successCode))
                .forEach(code -> assertResponseSchema(responses.path(code), "QuizApiError"));
    }

    private static void assertResponseSchema(JsonNode response, String schemaName) {
        assertEquals(
                "#/components/schemas/" + schemaName,
                response.path("content")
                        .path(MediaType.APPLICATION_JSON_VALUE)
                        .path("schema")
                        .path("$ref")
                        .asString());
    }

    private static void assertNullableString(JsonNode property) {
        assertEquals(Set.of("string", "null"), textValues(property.path("type")));
    }

    private static void assertNonNullableString(JsonNode property) {
        assertEquals("string", property.path("type").asString());
    }

    private String request(String title, String description, String authoringSource) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        request.put("description", description);
        request.put("authoringSource", authoringSource);
        return objectMapper.writeValueAsString(request);
    }

    private void configureUserToken(String token, UUID userId, List<String> roles) {
        when(jwtDecoder.decode(token))
                .thenReturn(jwt(
                        userId.toString(),
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                roles)));
    }

    private void configureServiceToken(String token) {
        when(jwtDecoder.decode(token))
                .thenReturn(jwt(
                        "assessment-service",
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.SERVICE,
                                QuizopiaTokenClaims.SCOPE,
                                List.of("quiz.teacher.write"))));
    }

    private static QuizDraftDetails details(
            UUID quizId, UUID ownerUserId, String title, String description, String source, Instant updatedAt) {
        return new QuizDraftDetails(
                new Quiz(quizId, ownerUserId, CREATED_AT),
                new QuizDraft(quizId, title, description, source, updatedAt));
    }

    private static Jwt jwt(String subject, Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.putAll(additionalClaims);
        return Jwt.withTokenValue("redacted-test-token")
                .header("alg", "RS256")
                .claims(values -> values.putAll(claims))
                .issuedAt(Instant.parse("2026-09-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-26T00:05:00Z"))
                .build();
    }

    private static Set<String> fieldNames(JsonNode node) {
        return new HashSet<>(node.propertyNames());
    }

    private static Set<String> textValues(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                FlywayAutoConfiguration.class
            })
    @Import({
        QuizController.class,
        QuizApiExceptionHandler.class,
        TeacherAuthoringPrincipalResolver.class,
        QuizopiaJwtAuthenticationConverter.class,
        QuizSecurityErrorHandler.class,
        SecurityConfiguration.class
    })
    static class TestApplication {
        @Bean
        QuizApplicationService quizApplicationService() {
            return mock(QuizApplicationService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }
}
