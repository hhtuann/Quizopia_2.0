package com.quizopia.classroom.api.internal;

import com.quizopia.classroom.application.*;
import com.quizopia.classroom.security.internal.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
public class ClassroomAuthorityController {
    private final ClassroomAuthorityService authority;

    public ClassroomAuthorityController(ClassroomAuthorityService authority) {
        this.authority = authority;
    }

    @PostMapping("/internal/classrooms/{classroomId}/teacher-authority")
    public Confirmation teacher(@PathVariable("classroomId") UUID classroomId, HttpServletRequest request) {
        UUID actor = InternalAuthorityFilter.actor(request);
        authority.teacher(actor, classroomId);
        return new Confirmation(classroomId, actor);
    }

    @PostMapping("/internal/classrooms/{classroomId}/student-membership")
    public Confirmation member(@PathVariable("classroomId") UUID classroomId, HttpServletRequest request) {
        UUID actor = InternalAuthorityFilter.actor(request);
        authority.member(actor, classroomId);
        return new Confirmation(classroomId, actor);
    }

    public record Confirmation(UUID classroomId, UUID actorId) {
        @Override
        public String toString() {
            return "Confirmation[redacted]";
        }
    }

    @ExceptionHandler({ClassroomNotFoundException.class, ClassroomOwnershipDeniedException.class})
    void inaccessible(HttpServletResponse response) throws IOException {
        InternalErrors.write(response, 404, "CLASSROOM_NOT_ACCESSIBLE");
    }

    @ExceptionHandler({
        org.springframework.dao.DataAccessException.class,
        org.springframework.transaction.TransactionException.class
    })
    void unavailable(HttpServletResponse response) throws IOException {
        InternalErrors.write(response, 503, "AUTHORITY_UNAVAILABLE");
    }
}
