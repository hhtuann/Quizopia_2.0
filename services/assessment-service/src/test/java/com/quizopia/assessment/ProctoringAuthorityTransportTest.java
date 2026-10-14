package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.api.ProctoringAuthorityController;
import com.quizopia.assessment.application.*;
import com.quizopia.assessment.configuration.ProctoringAuthorityProperties;
import com.quizopia.assessment.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.assessment.security.admission.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class ProctoringAuthorityTransportTest {
    @Test
    void insecureTransportAndSpoofedForwardingCannotReachAuthority() {
        var service = mock(ProctoringAuthorityService.class);
        var admission = mock(IdentityAdmissionClient.class);
        var controller = new ProctoringAuthorityController(
                new ProctoringAuthorityProperties(true, "proctoring", false),
                admission,
                new AdmissionProperties(),
                mock(JwtDecoder.class),
                new QuizopiaJwtAuthenticationConverter(),
                service,
                new MockEnvironment());
        var request = new MockHttpServletRequest(
                "POST", "/internal/proctoring/attempts/" + AdmissionTestTokens.ACTOR + "/authority");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");
        Jwt jwt = Jwt.withTokenValue("synthetic-service-token")
                .header("alg", "RS256")
                .issuer(AdmissionTestTokens.ISSUER)
                .subject("proctoring")
                .issuedAt(Instant.now().minusSeconds(1))
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("principal_type", "SERVICE")
                .claim("scope", List.of("assessment.proctoring-authority.read"))
                .build();
        var failure = assertThrows(
                PublicationFailure.class,
                () -> controller.authority(
                        AdmissionTestTokens.ACTOR,
                        new QuizopiaJwtAuthenticationConverter().convert(jwt),
                        request,
                        new MockHttpServletResponse()));
        assertEquals(403, failure.status());
        verifyNoInteractions(service, admission);
    }

    @Test
    void productionCannotEnableLoopbackException() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThrows(
                IllegalStateException.class,
                () -> new ProctoringAuthorityController(
                        new ProctoringAuthorityProperties(true, "proctoring", true),
                        mock(IdentityAdmissionClient.class),
                        new AdmissionProperties(),
                        mock(JwtDecoder.class),
                        new QuizopiaJwtAuthenticationConverter(),
                        mock(ProctoringAuthorityService.class),
                        environment));
    }

    @Test
    void enabledCapabilityRequiresExactCallerAndHasRedactedViews() {
        assertThrows(IllegalStateException.class, () -> new ProctoringAuthorityProperties(true, "", false));
        assertFalse(new ProctoringAuthorityProperties(false, "", false).enabled());
        var id = java.util.UUID.randomUUID();
        assertEquals(
                "ProctoringAuthorityView[redacted]",
                new ProctoringAuthorityService.View(id, "STUDENT", id, id, id, id, true, Instant.now(), Instant.now())
                        .toString());
    }
}
