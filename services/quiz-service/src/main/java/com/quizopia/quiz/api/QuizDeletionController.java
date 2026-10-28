package com.quizopia.quiz.api;

import com.quizopia.quiz.application.DeletionOperationService;
import com.quizopia.quiz.application.DeletionRequestResult;
import com.quizopia.quiz.application.InvalidDeletionRequestException;
import com.quizopia.quiz.security.TeacherAuthoringPrincipalResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "quizopia.deletion.enabled", havingValue = "true")
@SecurityScheme(
        name = QuizController.BEARER_SECURITY_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
@SecurityRequirement(name = QuizController.BEARER_SECURITY_SCHEME)
public final class QuizDeletionController {
    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final DeletionOperationService deletionOperationService;
    private final TeacherAuthoringPrincipalResolver teacherAuthoringPrincipalResolver;

    public QuizDeletionController(
            DeletionOperationService deletionOperationService,
            TeacherAuthoringPrincipalResolver teacherAuthoringPrincipalResolver) {
        this.deletionOperationService = deletionOperationService;
        this.teacherAuthoringPrincipalResolver = teacherAuthoringPrincipalResolver;
    }

    @PostMapping("/api/quizzes/{quizId}/deletion-requests")
    @Operation(
            description =
                    "Requests irreversible deletion of an owned quiz. Requires an Idempotency-Key header and exact title confirmation.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Deletion operation accepted or safely replayed",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizDeletionRequestAcceptedResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid request or missing title confirmation",
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
                description = "Quiz was not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Deletion conflict or deletion already pending",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class)))
    })
    public ResponseEntity<QuizDeletionRequestAcceptedResponse> requestDeletion(
            Authentication authentication,
            @PathVariable("quizId") UUID quizId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody QuizDeletionRequest request) {
        UUID callerUserId = teacherAuthoringPrincipalResolver.resolve(authentication);
        requireIdempotencyKey(idempotencyKey);
        String confirmTitle = request.confirmTitle();
        if (confirmTitle == null || confirmTitle.isBlank() || confirmTitle.length() > 300) {
            throw new InvalidDeletionRequestException("Confirm title must be 1 to 300 characters");
        }
        DeletionRequestResult result =
                deletionOperationService.requestDeletion(callerUserId, quizId, idempotencyKey, confirmTitle);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(new QuizDeletionRequestAcceptedResponse(
                        result.operationId(), result.status().name()));
    }

    @GetMapping("/api/quizzes/{quizId}/deletion-requests/{operationId}")
    @Operation(description = "Returns the owner-visible status of one deletion operation without PII.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Owned deletion operation status",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizDeletionStatusResponse.class))),
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
                                schema = @Schema(implementation = QuizApiError.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Deletion operation was not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = QuizApiError.class)))
    })
    public ResponseEntity<QuizDeletionStatusResponse> getStatus(
            Authentication authentication,
            @PathVariable("quizId") UUID quizId,
            @PathVariable("operationId") @Parameter(description = "Identifier of the deletion operation")
                    UUID operationId) {
        UUID callerUserId = teacherAuthoringPrincipalResolver.resolve(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(QuizDeletionStatusResponse.from(
                        deletionOperationService.getOperation(callerUserId, quizId, operationId)));
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidDeletionRequestException("Idempotency-Key must be 16 to 128 canonical characters");
        }
    }
}
