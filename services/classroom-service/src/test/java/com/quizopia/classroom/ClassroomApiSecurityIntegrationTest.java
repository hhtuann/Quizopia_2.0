package com.quizopia.classroom;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quizopia.classroom.api.ClassroomApiExceptionHandler;
import com.quizopia.classroom.api.ClassroomController;
import com.quizopia.classroom.application.ClassroomApplicationService;
import com.quizopia.classroom.application.ClassroomNotFoundException;
import com.quizopia.classroom.application.ClassroomOwnershipDeniedException;
import com.quizopia.classroom.application.CreateClassroomInput;
import com.quizopia.classroom.configuration.SecurityConfiguration;
import com.quizopia.classroom.domain.Classroom;
import com.quizopia.classroom.security.AuthenticatedUserIdResolver;
import com.quizopia.classroom.security.ClassroomSecurityErrorHandler;
import com.quizopia.classroom.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.classroom.security.QuizopiaTokenClaims;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
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

@SpringBootTest(classes = ClassroomApiSecurityIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassroomApiSecurityIntegrationTest {
    private static final String CREATE_REQUEST =
            """
            {"name":"Study group","subject":"Interdisciplinary","grade":"Mixed ages","description":"Notes"}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ClassroomApplicationService classroomApplicationService;

    @Autowired
    JwtDecoder jwtDecoder;

    @BeforeEach
    void resetMocks() {
        reset(classroomApplicationService, jwtDecoder);
    }

    @Test
    void noTokenReturnsMachineReadable401() throws Exception {
        mvc.perform(get("/classrooms/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").exists());
    }

    @Test
    void malformedAndMixedUserClaimsReturn401() throws Exception {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("malformed"))
                .thenReturn(jwt(
                        "not-a-uuid",
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("TEACHER"))));
        when(jwtDecoder.decode("mixed"))
                .thenReturn(jwt(
                        userId.toString(),
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("TEACHER"),
                                QuizopiaTokenClaims.SCOPE,
                                List.of("classroom.read"))));

        mvc.perform(get("/classrooms/{id}", UUID.randomUUID()).header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/classrooms/{id}", UUID.randomUUID()).header("Authorization", "Bearer mixed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void validServiceTokenCannotAccessUserEndpoints() throws Exception {
        configureServiceToken("service");

        mvc.perform(get("/classrooms/{id}", UUID.randomUUID()).header("Authorization", "Bearer service"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/classrooms")
                        .header("Authorization", "Bearer service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_REQUEST))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentAndAdminWithoutTeacherCannotCreate() throws Exception {
        configureUserToken("student", UUID.randomUUID(), List.of("STUDENT"));
        configureUserToken("admin", UUID.randomUUID(), List.of("STUDENT", "ADMIN"));

        mvc.perform(post("/classrooms")
                        .header("Authorization", "Bearer student")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_REQUEST))
                .andExpect(status().isForbidden());
        mvc.perform(post("/classrooms")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_REQUEST))
                .andExpect(status().isForbidden());
        verify(classroomApplicationService, never()).create(any(), any());
    }

    @Test
    void teacherCreatesClassroomWithJwtSubjectAsOwner() throws Exception {
        UUID callerUserId = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        configureUserToken("teacher", callerUserId, List.of("STUDENT", "TEACHER"));
        when(classroomApplicationService.create(any(), any()))
                .thenReturn(new Classroom(
                        classroomId, callerUserId, "Study group", "Interdisciplinary", "Mixed ages", "Notes"));

        mvc.perform(post("/classrooms")
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/classrooms/" + classroomId))
                .andExpect(jsonPath("$.id").value(classroomId.toString()))
                .andExpect(jsonPath("$.ownerTeacherUserId").value(callerUserId.toString()))
                .andExpect(jsonPath("$.subject").value("Interdisciplinary"))
                .andExpect(jsonPath("$.grade").value("Mixed ages"))
                .andExpect(jsonPath("$.createdAt").doesNotExist());

        ArgumentCaptor<UUID> callerCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<CreateClassroomInput> inputCaptor = ArgumentCaptor.forClass(CreateClassroomInput.class);
        verify(classroomApplicationService).create(callerCaptor.capture(), inputCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(callerUserId, callerCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(
                "Interdisciplinary", inputCaptor.getValue().subject());
        org.junit.jupiter.api.Assertions.assertEquals(
                "Mixed ages", inputCaptor.getValue().grade());
    }

    @Test
    void requestCannotSupplyAnArbitraryOwner() throws Exception {
        UUID callerUserId = UUID.randomUUID();
        configureUserToken("teacher", callerUserId, List.of("STUDENT", "TEACHER"));
        String requestWithOwner =
                """
                {"name":"Study group","subject":"Subject","grade":"Grade","ownerTeacherUserId":"%s"}
                """
                        .formatted(UUID.randomUUID());

        mvc.perform(post("/classrooms")
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithOwner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(classroomApplicationService, never()).create(any(), any());
    }

    @Test
    void requiredFieldsReturnMachineReadable400WithoutInternalDetails() throws Exception {
        configureUserToken("teacher", UUID.randomUUID(), List.of("STUDENT", "TEACHER"));

        mvc.perform(post("/classrooms")
                        .header("Authorization", "Bearer teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"subject\":\"\",\"grade\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/classrooms"))
                .andExpect(content().string(not(containsString("stackTrace"))))
                .andExpect(content().string(not(containsString("SQLException"))));
    }

    @Test
    void ownerGetsClassroom() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        configureUserToken("owner", owner, List.of("STUDENT"));
        when(classroomApplicationService.getOwnedClassroom(owner, classroomId))
                .thenReturn(new Classroom(classroomId, owner, "Class", "Subject", "Grade", null));

        mvc.perform(get("/classrooms/{id}", classroomId).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(classroomId.toString()))
                .andExpect(jsonPath("$.ownerTeacherUserId").value(owner.toString()));
        verify(classroomApplicationService).getOwnedClassroom(owner, classroomId);
    }

    @Test
    void ownershipDenialAndMissingClassroomHaveStableErrorMappings() throws Exception {
        UUID caller = UUID.randomUUID();
        UUID deniedId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        configureUserToken("user", caller, List.of("STUDENT"));
        when(classroomApplicationService.getOwnedClassroom(caller, deniedId))
                .thenThrow(new ClassroomOwnershipDeniedException(deniedId, caller));
        when(classroomApplicationService.getOwnedClassroom(caller, missingId))
                .thenThrow(new ClassroomNotFoundException(missingId));

        mvc.perform(get("/classrooms/{id}", deniedId).header("Authorization", "Bearer user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(content().string(not(containsString(caller.toString()))));
        mvc.perform(get("/classrooms/{id}", missingId).header("Authorization", "Bearer user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLASSROOM_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    void openApiContainsOnlyImplementedClassroomOperations() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode paths = objectMapper.readTree(body).path("paths");

        org.junit.jupiter.api.Assertions.assertTrue(paths.path("/classrooms").has("post"));
        org.junit.jupiter.api.Assertions.assertFalse(paths.path("/classrooms").has("get"));
        org.junit.jupiter.api.Assertions.assertTrue(
                paths.path("/classrooms/{classroomId}").has("get"));
        org.junit.jupiter.api.Assertions.assertFalse(paths.has("/classrooms/{classroomId}/members"));
        org.junit.jupiter.api.Assertions.assertFalse(paths.has("/classrooms/join"));
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
                                List.of("classroom.read"))));
    }

    private static Jwt jwt(String subject, Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.putAll(additionalClaims);
        return Jwt.withTokenValue("redacted-test-token")
                .header("alg", "RS256")
                .claims(values -> values.putAll(claims))
                .issuedAt(Instant.parse("2026-09-13T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-13T00:05:00Z"))
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
        ClassroomController.class,
        ClassroomApiExceptionHandler.class,
        AuthenticatedUserIdResolver.class,
        QuizopiaJwtAuthenticationConverter.class,
        ClassroomSecurityErrorHandler.class,
        SecurityConfiguration.class
    })
    static class TestApplication {
        @Bean
        ClassroomApplicationService classroomApplicationService() {
            return mock(ClassroomApplicationService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }
}
