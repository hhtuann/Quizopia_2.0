package com.quizopia.quiz;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
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
import com.quizopia.quiz.application.InvalidQuizLibraryRequestException;
import com.quizopia.quiz.application.InvalidQuizVersionRequestException;
import com.quizopia.quiz.application.QuizApplicationService;
import com.quizopia.quiz.application.QuizDraftDetails;
import com.quizopia.quiz.application.QuizDraftInput;
import com.quizopia.quiz.application.QuizDraftNotFoundException;
import com.quizopia.quiz.application.QuizLibraryItem;
import com.quizopia.quiz.application.QuizLibraryPage;
import com.quizopia.quiz.application.QuizLibraryService;
import com.quizopia.quiz.application.QuizMarkdownInvalidException;
import com.quizopia.quiz.application.QuizNotFoundException;
import com.quizopia.quiz.application.QuizOwnershipDeniedException;
import com.quizopia.quiz.application.QuizPublishResult;
import com.quizopia.quiz.application.QuizVersionNotFoundException;
import com.quizopia.quiz.application.QuizVersionPage;
import com.quizopia.quiz.application.QuizVersionQueryService;
import com.quizopia.quiz.application.QuizVersionSummary;
import com.quizopia.quiz.configuration.SecurityConfiguration;
import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDraft;
import com.quizopia.quiz.domain.QuizVersion;
import com.quizopia.quiz.domain.markdown.QuizMarkdownError;
import com.quizopia.quiz.domain.markdown.QuizMarkdownParser;
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
    QuizLibraryService quizLibraryService;

    @Autowired
    QuizVersionQueryService quizVersionQueryService;

    @Autowired
    JwtDecoder jwtDecoder;

    @BeforeEach
    void resetMocks() {
        reset(quizApplicationService, quizLibraryService, quizVersionQueryService, jwtDecoder);
    }

    @Test
    void teacherListsOnlyAuthenticatedOwnersLibraryWithDefaultLimitAndMetadataOnlyItems() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID ignoredClientOwner = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        configureUserToken("teacher-list", ownerUserId, List.of("TEACHER"));
        when(quizLibraryService.listOwned(ownerUserId, 20, null))
                .thenReturn(new QuizLibraryPage(
                        List.of(new QuizLibraryItem(quizId, "Title", "Description", CREATED_AT, UPDATED_AT, 3)),
                        "opaque-next"));

        String body = mvc.perform(get("/api/quizzes")
                        .queryParam("ownerUserId", ignoredClientOwner.toString())
                        .header("Authorization", "Bearer teacher-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Title"))
                .andExpect(jsonPath("$.items[0].description").value("Description"))
                .andExpect(jsonPath("$.items[0].createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.items[0].updatedAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.items[0].latestVersionNumber").value(3))
                .andExpect(jsonPath("$.items[0].authoringSource").doesNotExist())
                .andExpect(jsonPath("$.items[0].ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("opaque-next"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(Set.of("items", "nextCursor"), fieldNames(objectMapper.readTree(body)));
        assertEquals(
                Set.of("quizId", "title", "description", "createdAt", "updatedAt", "latestVersionNumber"),
                fieldNames(objectMapper.readTree(body).path("items").get(0)));
        verify(quizLibraryService).listOwned(ownerUserId, 20, null);
    }

    @Test
    void listAcceptsCustomAndMaxLimitsAndMapsInvalidLimitOrCursorTo400() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        configureUserToken("teacher-list-limits", ownerUserId, List.of("TEACHER"));
        QuizLibraryPage empty = new QuizLibraryPage(List.of(), null);
        when(quizLibraryService.listOwned(ownerUserId, 7, "cursor-token")).thenReturn(empty);
        when(quizLibraryService.listOwned(ownerUserId, 100, null)).thenReturn(empty);
        when(quizLibraryService.listOwned(ownerUserId, 0, null))
                .thenThrow(new InvalidQuizLibraryRequestException("invalid"));
        when(quizLibraryService.listOwned(ownerUserId, -1, null))
                .thenThrow(new InvalidQuizLibraryRequestException("invalid"));
        when(quizLibraryService.listOwned(ownerUserId, 101, null))
                .thenThrow(new InvalidQuizLibraryRequestException("invalid"));
        when(quizLibraryService.listOwned(ownerUserId, 20, "malformed"))
                .thenThrow(new InvalidQuizLibraryRequestException("invalid"));

        mvc.perform(get("/api/quizzes")
                        .queryParam("limit", "7")
                        .queryParam("cursor", "cursor-token")
                        .header("Authorization", "Bearer teacher-list-limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/quizzes")
                        .queryParam("limit", "100")
                        .header("Authorization", "Bearer teacher-list-limits"))
                .andExpect(status().isOk());
        for (String invalid : List.of("0", "-1", "101")) {
            mvc.perform(get("/api/quizzes")
                            .queryParam("limit", invalid)
                            .header("Authorization", "Bearer teacher-list-limits"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/quizzes")
                        .queryParam("limit", "not-an-int")
                        .header("Authorization", "Bearer teacher-list-limits"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/quizzes")
                        .queryParam("cursor", "malformed")
                        .header("Authorization", "Bearer teacher-list-limits"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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
    void ownerTeacherPublishesNewVersionThenReusesUnchangedLatestWithoutResponseLeakage() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        configureUserToken("teacher", ownerUserId, List.of("TEACHER"));
        QuizVersion version = publishedVersion(versionId, quizId, 1);
        when(quizApplicationService.publishOwnedDraft(ownerUserId, quizId))
                .thenReturn(new QuizPublishResult(version, true), new QuizPublishResult(version, false));

        mvc.perform(post("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer teacher"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(versionId.toString()))
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.structuredContent").doesNotExist())
                .andExpect(jsonPath("$.sourceSnapshot").doesNotExist())
                .andExpect(jsonPath("$.titleSnapshot").doesNotExist())
                .andExpect(jsonPath("$.descriptionSnapshot").doesNotExist());

        mvc.perform(post("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer teacher"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId.toString()))
                .andExpect(jsonPath("$.versionNumber").value(1));
        verify(quizApplicationService, org.mockito.Mockito.times(2)).publishOwnedDraft(ownerUserId, quizId);
    }

    @Test
    void ownerTeacherListsPublishedVersionMetadataWithoutSnapshotContent() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        configureUserToken("teacher-version-list", ownerUserId, List.of("TEACHER"));
        when(quizVersionQueryService.listOwned(ownerUserId, quizId, 20, null))
                .thenReturn(new QuizVersionPage(
                        List.of(new QuizVersionSummary(versionId, quizId, 2, "Historical title", null, 1, CREATED_AT)),
                        "opaque-next"));

        String body = mvc.perform(get("/api/quizzes/{quizId}/versions", quizId)
                        .header("Authorization", "Bearer teacher-version-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(versionId.toString()))
                .andExpect(jsonPath("$.items[0].quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.items[0].versionNumber").value(2))
                .andExpect(jsonPath("$.items[0].titleSnapshot").value("Historical title"))
                .andExpect(jsonPath("$.items[0].descriptionSnapshot").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].contentSchemaVersion").value(1))
                .andExpect(jsonPath("$.items[0].createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.items[0].sourceSnapshot").doesNotExist())
                .andExpect(jsonPath("$.items[0].structuredContent").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("opaque-next"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(Set.of("items", "nextCursor"), fieldNames(objectMapper.readTree(body)));
        verify(quizVersionQueryService).listOwned(ownerUserId, quizId, 20, null);
    }

    @Test
    void ownerTeacherReadsExactImmutablePublishedSnapshot() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        QuizVersion version = publishedVersion(UUID.randomUUID(), quizId, 1);
        configureUserToken("teacher-version-detail", ownerUserId, List.of("TEACHER"));
        when(quizVersionQueryService.getOwned(ownerUserId, quizId, 1)).thenReturn(version);

        mvc.perform(get("/api/quizzes/{quizId}/versions/{versionNumber}", quizId, 1)
                        .header("Authorization", "Bearer teacher-version-detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(version.id().toString()))
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.titleSnapshot").value(version.titleSnapshot()))
                .andExpect(jsonPath("$.descriptionSnapshot").value(version.descriptionSnapshot()))
                .andExpect(jsonPath("$.sourceSnapshot").value(version.sourceSnapshot()))
                .andExpect(jsonPath("$.structuredContent.questions.length()").value(1))
                .andExpect(jsonPath("$.contentSchemaVersion").value(1))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()));

        verify(quizVersionQueryService).getOwned(ownerUserId, quizId, 1);
    }

    @Test
    void versionReadsMapOwnershipNotFoundAndInvalidRequestsToExistingConventions() throws Exception {
        UUID callerUserId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID deniedQuizId = UUID.randomUUID();
        configureUserToken("teacher-version-errors", callerUserId, List.of("TEACHER"));
        when(quizVersionQueryService.listOwned(callerUserId, quizId, 0, null))
                .thenThrow(new InvalidQuizVersionRequestException("invalid"));
        when(quizVersionQueryService.listOwned(callerUserId, quizId, 20, "malformed"))
                .thenThrow(new InvalidQuizVersionRequestException("invalid"));
        when(quizVersionQueryService.listOwned(callerUserId, deniedQuizId, 20, null))
                .thenThrow(new QuizOwnershipDeniedException(deniedQuizId, callerUserId));
        when(quizVersionQueryService.getOwned(callerUserId, quizId, 99))
                .thenThrow(new QuizVersionNotFoundException(quizId, 99));

        mvc.perform(get("/api/quizzes/{quizId}/versions", quizId)
                        .queryParam("limit", "0")
                        .header("Authorization", "Bearer teacher-version-errors"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/quizzes/{quizId}/versions", quizId)
                        .queryParam("cursor", "malformed")
                        .header("Authorization", "Bearer teacher-version-errors"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/quizzes/{quizId}/versions", deniedQuizId)
                        .header("Authorization", "Bearer teacher-version-errors"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/quizzes/{quizId}/versions/{versionNumber}", quizId, 99)
                        .header("Authorization", "Bearer teacher-version-errors"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_VERSION_NOT_FOUND"));
    }

    @Test
    void studentAndServiceCannotReadPublishedVersions() throws Exception {
        UUID quizId = UUID.randomUUID();
        configureUserToken("student-version-read", UUID.randomUUID(), List.of("STUDENT"));
        configureServiceToken("service-version-read");

        for (String token : List.of("student-version-read", "service-version-read")) {
            mvc.perform(get("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mvc.perform(get("/api/quizzes/{quizId}/versions/{versionNumber}", quizId, 1)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        verify(quizVersionQueryService, never()).listOwned(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(quizVersionQueryService, never()).getOwned(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void invalidQuizMarkdownReturnsStructured400WithTraceIdAndAllErrors() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        configureUserToken("teacher", ownerUserId, List.of("TEACHER"));
        when(quizApplicationService.publishOwnedDraft(ownerUserId, quizId))
                .thenThrow(new QuizMarkdownInvalidException(List.of(
                        new QuizMarkdownError("EMPTY_STEM", 1, 1, 1, "Question stem must not be blank"),
                        new QuizMarkdownError("MISSING_OPTION", 1, 3, 1, "Expected option B."))));

        mvc.perform(post("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer teacher"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUIZ_MARKDOWN_INVALID"))
                .andExpect(jsonPath("$.message").value("Quiz Markdown validation failed."))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/quizzes/" + quizId + "/versions"))
                .andExpect(jsonPath("$.traceId", not(emptyOrNullString())))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].code").value("EMPTY_STEM"))
                .andExpect(jsonPath("$.errors[0].questionNumber").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(1))
                .andExpect(jsonPath("$.errors[0].column").value(1))
                .andExpect(jsonPath("$.errors[0].message").value("Question stem must not be blank"))
                .andExpect(jsonPath("$.errors[1].code").value("MISSING_OPTION"));
    }

    @Test
    void publishMapsMissingQuizOwnershipDenialAndMissingDraft() throws Exception {
        UUID callerUserId = UUID.randomUUID();
        UUID missingQuizId = UUID.randomUUID();
        UUID deniedQuizId = UUID.randomUUID();
        UUID missingDraftQuizId = UUID.randomUUID();
        configureUserToken("teacher", callerUserId, List.of("TEACHER"));
        when(quizApplicationService.publishOwnedDraft(callerUserId, missingQuizId))
                .thenThrow(new QuizNotFoundException(missingQuizId));
        when(quizApplicationService.publishOwnedDraft(callerUserId, deniedQuizId))
                .thenThrow(new QuizOwnershipDeniedException(deniedQuizId, callerUserId));
        when(quizApplicationService.publishOwnedDraft(callerUserId, missingDraftQuizId))
                .thenThrow(new QuizDraftNotFoundException(missingDraftQuizId));

        mvc.perform(post("/api/quizzes/{quizId}/versions", missingQuizId).header("Authorization", "Bearer teacher"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_NOT_FOUND"));
        mvc.perform(post("/api/quizzes/{quizId}/versions", deniedQuizId).header("Authorization", "Bearer teacher"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/quizzes/{quizId}/versions", missingDraftQuizId)
                        .header("Authorization", "Bearer teacher"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_DRAFT_NOT_FOUND"));
    }

    @Test
    void studentAdminWithoutTeacherAndServiceCannotPublish() throws Exception {
        UUID quizId = UUID.randomUUID();
        configureUserToken("student", UUID.randomUUID(), List.of("STUDENT"));
        configureUserToken("admin", UUID.randomUUID(), List.of("ADMIN"));
        configureServiceToken("service");

        mvc.perform(post("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer student"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer admin"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer service"))
                .andExpect(status().isForbidden());
        verify(quizApplicationService, never()).publishOwnedDraft(any(), any());
    }

    @Test
    void studentAndAdminWithoutTeacherAndServiceAreDeniedAtTheRoute() throws Exception {
        UUID quizId = UUID.randomUUID();
        configureUserToken("student", UUID.randomUUID(), List.of("STUDENT"));
        configureUserToken("admin", UUID.randomUUID(), List.of("ADMIN"));
        configureServiceToken("service");

        mvc.perform(get("/api/quizzes").header("Authorization", "Bearer student"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/quizzes").header("Authorization", "Bearer admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/quizzes").header("Authorization", "Bearer service"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

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
        verify(quizLibraryService, never()).listOwned(any(), any(Integer.class), any());
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
        mvc.perform(get("/api/quizzes").header("Authorization", "Bearer mixed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer mixed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(content().string(not(containsString("quiz.write"))))
                .andExpect(content().string(not(containsString(userId.toString()))));
        mvc.perform(post("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer mixed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
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

        assertEquals(
                Set.of(
                        "/api/quizzes",
                        "/api/quizzes/{quizId}/draft",
                        "/api/quizzes/{quizId}/versions",
                        "/api/quizzes/{quizId}/versions/{versionNumber}"),
                fieldNames(paths));
        JsonNode list = paths.path("/api/quizzes").path("get");
        JsonNode create = paths.path("/api/quizzes").path("post");
        JsonNode read = paths.path("/api/quizzes/{quizId}/draft").path("get");
        JsonNode update = paths.path("/api/quizzes/{quizId}/draft").path("put");
        JsonNode publish = paths.path("/api/quizzes/{quizId}/versions").path("post");
        JsonNode versionHistory = paths.path("/api/quizzes/{quizId}/versions").path("get");
        JsonNode versionDetail =
                paths.path("/api/quizzes/{quizId}/versions/{versionNumber}").path("get");

        assertFalse(document.has("security"));
        JsonNode securitySchemes = document.path("components").path("securitySchemes");
        assertEquals(Set.of("quizopiaBearerAuth"), fieldNames(securitySchemes));
        JsonNode bearerScheme = securitySchemes.path("quizopiaBearerAuth");
        assertEquals("http", bearerScheme.path("type").asString());
        assertEquals("bearer", bearerScheme.path("scheme").asString());
        assertEquals("JWT", bearerScheme.path("bearerFormat").asString());
        assertOperationRequiresBearer(list);
        assertOperationRequiresBearer(create);
        assertOperationRequiresBearer(read);
        assertOperationRequiresBearer(update);
        assertOperationRequiresBearer(publish);
        assertOperationRequiresBearer(versionHistory);
        assertOperationRequiresBearer(versionDetail);

        assertFalse(create.isMissingNode());
        assertFalse(list.isMissingNode());
        assertFalse(read.isMissingNode());
        assertFalse(update.isMissingNode());
        assertFalse(publish.isMissingNode());
        assertFalse(versionHistory.isMissingNode());
        assertFalse(versionDetail.isMissingNode());
        assertFalse(paths.path("/api/quizzes/{quizId}/draft").has("delete"));
        assertResponses(list, Set.of("200", "400", "401", "403"), "200", "QuizLibraryResponse");
        assertResponses(create, Set.of("201", "400", "401", "403"), "201");
        assertResponses(read, Set.of("200", "400", "401", "403", "404"), "200");
        assertResponses(update, Set.of("200", "400", "401", "403", "404"), "200");
        assertEquals(Set.of("200", "201", "400", "401", "403", "404"), fieldNames(publish.path("responses")));
        assertResponseSchema(publish.path("responses").path("200"), "QuizVersionResponse");
        assertResponseSchema(publish.path("responses").path("201"), "QuizVersionResponse");
        assertResponseSchema(publish.path("responses").path("401"), "QuizApiError");
        assertResponseSchema(publish.path("responses").path("403"), "QuizApiError");
        assertResponseSchema(publish.path("responses").path("404"), "QuizApiError");
        assertResponses(versionHistory, Set.of("200", "400", "401", "403", "404"), "200", "QuizVersionHistoryResponse");
        assertResponses(versionDetail, Set.of("200", "400", "401", "403", "404"), "200", "QuizVersionDetailResponse");

        JsonNode schemas = document.path("components").path("schemas");
        assertEquals(
                Set.of(
                        "QuizApiError",
                        "QuizDraftRequest",
                        "QuizDraftResponse",
                        "QuizLibraryItemResponse",
                        "QuizLibraryResponse",
                        "QuizMarkdownErrorResponse",
                        "QuizMarkdownValidationErrorResponse",
                        "QuizContent",
                        "QuizOption",
                        "QuizQuestion",
                        "QuizVersionDetailResponse",
                        "QuizVersionHistoryResponse",
                        "QuizVersionSummaryResponse",
                        "QuizVersionResponse"),
                fieldNames(schemas));
        assertEquals(
                Set.of("items", "nextCursor"),
                fieldNames(schemas.path("QuizLibraryResponse").path("properties")));
        assertEquals(
                Set.of("quizId", "title", "description", "createdAt", "updatedAt", "latestVersionNumber"),
                fieldNames(schemas.path("QuizLibraryItemResponse").path("properties")));
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
        assertEquals(
                Set.of("id", "quizId", "versionNumber", "createdAt"),
                fieldNames(schemas.path("QuizVersionResponse").path("properties")));
        assertEquals(
                Set.of("items", "nextCursor"),
                fieldNames(schemas.path("QuizVersionHistoryResponse").path("properties")));
        assertEquals(
                Set.of(
                        "id",
                        "quizId",
                        "versionNumber",
                        "titleSnapshot",
                        "descriptionSnapshot",
                        "contentSchemaVersion",
                        "createdAt"),
                fieldNames(schemas.path("QuizVersionSummaryResponse").path("properties")));
        assertEquals(
                Set.of(
                        "id",
                        "quizId",
                        "versionNumber",
                        "titleSnapshot",
                        "descriptionSnapshot",
                        "sourceSnapshot",
                        "structuredContent",
                        "contentSchemaVersion",
                        "createdAt"),
                fieldNames(schemas.path("QuizVersionDetailResponse").path("properties")));
        assertEquals(
                Set.of("code", "message", "status", "path", "traceId", "errors"),
                fieldNames(schemas.path("QuizMarkdownValidationErrorResponse").path("properties")));
        assertEquals(
                Set.of("code", "questionNumber", "line", "column", "message"),
                fieldNames(schemas.path("QuizMarkdownErrorResponse").path("properties")));
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
        assertResponses(operation, expectedCodes, successCode, "QuizDraftResponse");
    }

    private static void assertResponses(
            JsonNode operation, Set<String> expectedCodes, String successCode, String successSchema) {
        JsonNode responses = operation.path("responses");
        assertEquals(expectedCodes, fieldNames(responses));
        assertResponseSchema(responses.path(successCode), successSchema);
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

    private static QuizVersion publishedVersion(UUID id, UUID quizId, int versionNumber) {
        return new QuizVersion(
                id,
                quizId,
                versionNumber,
                "hidden title",
                "hidden description",
                "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n",
                new QuizMarkdownParser()
                        .parse("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n")
                        .content()
                        .orElseThrow(),
                1,
                CREATED_AT);
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
        QuizLibraryService quizLibraryService() {
            return mock(QuizLibraryService.class);
        }

        @Bean
        QuizVersionQueryService quizVersionQueryService() {
            return mock(QuizVersionQueryService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }
}
