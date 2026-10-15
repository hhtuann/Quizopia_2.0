package com.quizopia.classroom.api.internal;

import com.quizopia.classroom.application.*;
import com.quizopia.classroom.security.internal.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@ConditionalOnProperty(name = "quizopia.internal-authority.proctoring-membership-enabled", havingValue = "true")
public class ProctoringMembershipController {
    private final ProctoringMembershipAuthority authority;
    private final Semaphore capacity = new Semaphore(8);

    public ProctoringMembershipController(ProctoringMembershipAuthority authority) {
        this.authority = authority;
    }

    @PostMapping("/internal/classrooms/{classroomId}/proctoring-students/{studentId}/membership")
    public ProctoringMembershipAuthority.Confirmation membership(
            @PathVariable("classroomId") UUID classroomId,
            @PathVariable("studentId") UUID studentId,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        if (!capacity.tryAcquire()) {
            InternalErrors.write(response, 503, "AUTHORITY_UNAVAILABLE");
            return null;
        }
        try {
            var result = authority.confirm(InternalAuthorityFilter.actor(request), classroomId, studentId);
            var now = Instant.now();
            var user = (Jwt) request.getAttribute(InternalAuthorityFilter.ACTOR);
            if (user == null
                    || user.getExpiresAt() == null
                    || !user.getExpiresAt().isAfter(now)) {
                InternalErrors.write(response, 401, "USER_EVIDENCE_INVALID");
                return null;
            }
            if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken service)
                    || service.getToken().getExpiresAt() == null
                    || !service.getToken().getExpiresAt().isAfter(now)) {
                InternalErrors.write(response, 401, "SERVICE_AUTHENTICATION_INVALID");
                return null;
            }
            return result;
        } finally {
            capacity.release();
        }
    }

    @ExceptionHandler(ClassroomNotFoundException.class)
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
