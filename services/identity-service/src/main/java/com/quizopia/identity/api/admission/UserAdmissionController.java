package com.quizopia.identity.api.admission;

import com.quizopia.identity.application.admission.AdmissionFailure;
import com.quizopia.identity.application.admission.UserAdmissionService;
import com.quizopia.identity.configuration.UserAdmissionConfiguration;
import com.quizopia.identity.security.admission.AdmissionTokenVerifier;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "quizopia.identity.user-admission", name = "enabled", havingValue = "true")
public class UserAdmissionController {
    private final AdmissionTokenVerifier verifier;
    private final UserAdmissionService admission;

    public UserAdmissionController(AdmissionTokenVerifier verifier, UserAdmissionService admission) {
        this.verifier = verifier;
        this.admission = admission;
    }

    @PostMapping(UserAdmissionConfiguration.PATH)
    public UserAdmissionService.AdmissionResponse admit(HttpServletRequest request) throws IOException {
        var headers = Collections.list(request.getHeaders(UserAdmissionConfiguration.USER_HEADER));
        if (headers.size() != 1
                || headers.getFirst().length() > 8192
                || !headers.getFirst().matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                || request.getQueryString() != null
                || request.getHeader("Cookie") != null
                || request.getInputStream().read() != -1) throw AdmissionFailure.malformed();
        var jwt = verifier.user(headers.getFirst());
        var result = admission.admit(
                UUID.fromString(jwt.getSubject()), jwt.getIssuedAt(), jwt.getClaimAsStringList("roles"));
        if (!Instant.now().isBefore(jwt.getExpiresAt())) throw AdmissionFailure.invalidUser();
        return result;
    }

    @ExceptionHandler(AdmissionFailure.class)
    void failure(AdmissionFailure failure, HttpServletResponse response) throws IOException {
        AdmissionErrors.write(response, failure.status(), failure.code());
    }
}
