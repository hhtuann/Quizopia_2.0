package com.quizopia.classroom.api;

import com.quizopia.classroom.application.ClassroomApplicationService;
import com.quizopia.classroom.application.ClassroomDirectory;
import com.quizopia.classroom.application.ClassroomDirectoryService;
import com.quizopia.classroom.application.CreateClassroomInput;
import com.quizopia.classroom.domain.Classroom;
import com.quizopia.classroom.security.AuthenticatedUserIdResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/classrooms")
public final class ClassroomController {
    private final ClassroomApplicationService classroomApplicationService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;
    private final ClassroomDirectoryService directory;

    public ClassroomController(
            ClassroomApplicationService classroomApplicationService,
            AuthenticatedUserIdResolver authenticatedUserIdResolver,
            ClassroomDirectoryService directory) {
        this.classroomApplicationService = classroomApplicationService;
        this.authenticatedUserIdResolver = authenticatedUserIdResolver;
        this.directory = directory;
    }

    @GetMapping
    @Operation(
            operationId = "listOwnedClassrooms",
            summary = "List owned classrooms",
            description =
                    "Requires USER + TEACHER and fresh LD-05 admission. Immutable createdAt DESC, id DESC keyset ordering; owner-bound opaque cursors. No membership or invitation data.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Owned classroom page",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ClassroomDirectory.Page.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid limit or cursor",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ClassroomApiError.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Unauthenticated",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                type = "object",
                                                requiredProperties = {"code"}))),
        @ApiResponse(
                responseCode = "403",
                description = "Teacher access denied",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                type = "object",
                                                requiredProperties = {"code"}))),
        @ApiResponse(
                responseCode = "503",
                description = "Required authority unavailable",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                type = "object",
                                                requiredProperties = {"code"})))
    })
    public ClassroomDirectory.Page listOwned(
            Authentication authentication,
            @Parameter(
                            description = "Page size, 1-100; default 20",
                            schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(name = "limit", defaultValue = "20")
                    int limit,
            @Parameter(
                            description = "Opaque nextCursor from the previous page",
                            schema = @Schema(minLength = 1, maxLength = 512))
                    @RequestParam(name = "cursor", required = false)
                    String cursor) {
        return directory.listOwned(authenticatedUserIdResolver.resolve(authentication), limit, cursor);
    }

    @PostMapping
    public ResponseEntity<ClassroomResponse> create(
            Authentication authentication, @Valid @RequestBody CreateClassroomRequest request) {
        UUID callerUserId = authenticatedUserIdResolver.resolve(authentication);
        Classroom classroom = classroomApplicationService.create(
                callerUserId,
                new CreateClassroomInput(request.name(), request.subject(), request.grade(), request.description()));
        return ResponseEntity.created(URI.create("/classrooms/" + classroom.id()))
                .body(ClassroomResponse.from(classroom));
    }

    @GetMapping("/{classroomId}")
    public ClassroomResponse getOwnedClassroom(
            Authentication authentication, @PathVariable("classroomId") UUID classroomId) {
        UUID callerUserId = authenticatedUserIdResolver.resolve(authentication);
        return ClassroomResponse.from(classroomApplicationService.getOwnedClassroom(callerUserId, classroomId));
    }
}
