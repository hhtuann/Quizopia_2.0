package com.quizopia.classroom.api;

import com.quizopia.classroom.application.ClassroomApplicationService;
import com.quizopia.classroom.application.CreateClassroomInput;
import com.quizopia.classroom.domain.Classroom;
import com.quizopia.classroom.security.AuthenticatedUserIdResolver;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/classrooms")
public final class ClassroomController {
    private final ClassroomApplicationService classroomApplicationService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;

    public ClassroomController(
            ClassroomApplicationService classroomApplicationService,
            AuthenticatedUserIdResolver authenticatedUserIdResolver) {
        this.classroomApplicationService = classroomApplicationService;
        this.authenticatedUserIdResolver = authenticatedUserIdResolver;
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
