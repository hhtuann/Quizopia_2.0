package com.quizopia.quiz.api;

import com.quizopia.quiz.application.QuizApplicationService;
import com.quizopia.quiz.application.QuizDraftDetails;
import com.quizopia.quiz.application.QuizDraftInput;
import com.quizopia.quiz.security.TeacherAuthoringPrincipalResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quizzes")
@SecurityScheme(
        name = QuizController.BEARER_SECURITY_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
@SecurityRequirement(name = QuizController.BEARER_SECURITY_SCHEME)
public final class QuizController {
    static final String BEARER_SECURITY_SCHEME = "quizopiaBearerAuth";

    private final QuizApplicationService quizApplicationService;
    private final TeacherAuthoringPrincipalResolver teacherAuthoringPrincipalResolver;

    public QuizController(
            QuizApplicationService quizApplicationService,
            TeacherAuthoringPrincipalResolver teacherAuthoringPrincipalResolver) {
        this.quizApplicationService = quizApplicationService;
        this.teacherAuthoringPrincipalResolver = teacherAuthoringPrincipalResolver;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(description = "Requires an authenticated Quizopia USER token with the explicit TEACHER role.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Quiz and initial draft created",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizDraftResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid request",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is required",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Teacher authoring access is denied",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class)))
    })
    public ResponseEntity<QuizDraftResponse> create(
            Authentication authentication, @RequestBody QuizDraftRequest request) {
        UUID callerUserId = teacherAuthoringPrincipalResolver.resolve(authentication);
        QuizDraftDetails created = quizApplicationService.create(callerUserId, input(request));
        URI location = URI.create("/api/quizzes/" + created.quiz().id() + "/draft");
        return ResponseEntity.created(location).body(QuizDraftResponse.from(created));
    }

    @GetMapping("/{quizId}/draft")
    @Operation(description = "Requires an authenticated Quizopia USER token with the explicit TEACHER role.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Owned current draft",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizDraftResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid request",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is required",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Teacher authoring access or quiz ownership is denied",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Quiz or current draft was not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class)))
    })
    public QuizDraftResponse getOwnedDraft(Authentication authentication, @PathVariable("quizId") UUID quizId) {
        UUID callerUserId = teacherAuthoringPrincipalResolver.resolve(authentication);
        return QuizDraftResponse.from(quizApplicationService.getOwnedDraft(callerUserId, quizId));
    }

    @PutMapping("/{quizId}/draft")
    @Operation(description = "Requires an authenticated Quizopia USER token with the explicit TEACHER role.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current draft replaced",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizDraftResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid request",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is required",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Teacher authoring access or quiz ownership is denied",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Quiz or current draft was not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class)))
    })
    public QuizDraftResponse updateOwnedDraft(
            Authentication authentication, @PathVariable("quizId") UUID quizId, @RequestBody QuizDraftRequest request) {
        UUID callerUserId = teacherAuthoringPrincipalResolver.resolve(authentication);
        return QuizDraftResponse.from(quizApplicationService.updateOwnedDraft(callerUserId, quizId, input(request)));
    }

    private static QuizDraftInput input(QuizDraftRequest request) {
        return new QuizDraftInput(request.title(), request.description(), request.authoringSource());
    }
}
