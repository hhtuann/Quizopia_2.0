package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.gateway.security.admission.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

class TeacherAssessmentRouteTest {
    String id = "00000000-0000-4000-8000-000000000001", base = "/api/assessments/publications/" + id;

    @Test
    void exactRoleInventoryHasNoWildcardOrStudentAnswerReviewEscalation() {
        for (String suffix : List.of(
                "/student-overview",
                "/students",
                "/students/" + id + "/attempts",
                "/attempts/" + id + "/teacher-review",
                "/attempts/" + id + "/answer-audit")) {
            assertEquals("TEACHER", PublicationRoutes.requiredRole("GET", base + suffix));
            assertNull(TeacherAssessmentRoutes.role("DELETE", base + suffix));
            assertNull(TeacherAssessmentRoutes.role("GET", base + suffix + "/"));
        }
        assertEquals(
                "STUDENT", PublicationRoutes.requiredRole("POST", base + "/attempts/" + id + "/answer-observations"));
        assertEquals("STUDENT", PublicationRoutes.requiredRole("GET", "/api/assessments/attempts/" + id + "/review"));
        assertNull(TeacherAssessmentRoutes.role("GET", base + "/attempts/" + id + "/answers"));
    }

    @Test
    void newRoutesRequireExistingResultsGateAndFreshAdmissionAndBoundBodies() {
        var admission = mock(IdentityAdmissionClient.class);
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
        var flags = new AttemptFeatureProperties(true, true, false);
        var publication = new PublicationProperties();
        publication.setEnabled(true);
        var filter = new UserAdmissionWebFilter(admission, publication, flags);
        var jwt = Jwt.withTokenValue("a.b.c")
                .header("alg", "RS256")
                .subject(id)
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        var token = new JwtAuthenticationToken(jwt);
        var request = MockServerHttpRequest.get(base + "/students?limit=20")
                .header("Authorization", "Bearer a.b.c")
                .build();
        var exchange = MockServerWebExchange.builder(request).principal(token).build();
        var forwarded = new java.util.concurrent.atomic.AtomicInteger();
        filter.filter(exchange, e -> {
                    forwarded.incrementAndGet();
                    return Mono.empty();
                })
                .block();
        assertEquals(1, forwarded.get());
        verify(admission).admit(jwt, "TEACHER");
        var closed =
                new UserAdmissionWebFilter(admission, publication, new AttemptFeatureProperties(true, false, false));
        var denied = MockServerWebExchange.builder(request).principal(token).build();
        closed.filter(denied, e -> {
                    fail("disabled result route forwarded");
                    return Mono.empty();
                })
                .block();
        assertEquals(503, denied.getResponse().getStatusCode().value());
        var invalid = MockServerWebExchange.builder(
                        MockServerHttpRequest.method(HttpMethod.GET, base + "/student-overview?unexpected=true")
                                .header("Authorization", "Bearer a.b.c")
                                .build())
                .principal(token)
                .build();
        filter.filter(invalid, e -> {
                    fail("query reached backend");
                    return Mono.empty();
                })
                .block();
        assertEquals(400, invalid.getResponse().getStatusCode().value());
    }
}
