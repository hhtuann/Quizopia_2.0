package com.quizopia.identity.security.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class QuizopiaJwtAuthenticationConverterTest {
    private final QuizopiaJwtAuthenticationConverter converter = new QuizopiaJwtAuthenticationConverter();

    @Test
    void userRolesMapToRoleAuthoritiesAndUserMarker() {
        UUID userId = UUID.randomUUID();
        Jwt jwt = jwt(
                userId.toString(),
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.USER,
                        QuizopiaTokenClaims.ROLES,
                        List.of("STUDENT", "TEACHER")));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertEquals(userId.toString(), authentication.getName());
        assertEquals(Set.of("TOKEN_USER", "ROLE_STUDENT", "ROLE_TEACHER"), authorities(authentication));
    }

    @Test
    void serviceScopeArrayWithOneValueMapsToExactServiceAuthorities() {
        Jwt jwt = jwt(
                "assessment-service",
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.SERVICE,
                        QuizopiaTokenClaims.SCOPE,
                        List.of("classroom.membership.read")));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertEquals("assessment-service", authentication.getName());
        assertEquals(Set.of("TOKEN_SERVICE", "SCOPE_classroom.membership.read"), authorities(authentication));
        assertFalse(authorities(authentication).stream().anyMatch(authority -> authority.startsWith("ROLE_")));
    }

    @Test
    void serviceScopeArrayWithMultipleValuesMapsToExactServiceAuthorities() {
        Jwt jwt = jwt(
                "assessment-service",
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.SERVICE,
                        QuizopiaTokenClaims.SCOPE,
                        List.of("classroom.membership.read", "quiz.snapshot.read")));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertEquals(
                Set.of("TOKEN_SERVICE", "SCOPE_classroom.membership.read", "SCOPE_quiz.snapshot.read"),
                authorities(authentication));
        assertFalse(authorities(authentication).stream().anyMatch(authority -> authority.startsWith("ROLE_")));
    }

    @Test
    void serviceScopeMustBePresentAndNonEmpty() {
        assertThrowsExactly(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(
                        "assessment-service",
                        Map.of(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.SERVICE))));
        assertInvalidServiceScope(List.of());
    }

    @Test
    void serviceScopeRejectsScalarAndObjectRawTypes() {
        assertInvalidServiceScope("classroom.membership.read");
        assertInvalidServiceScope(42);
        assertInvalidServiceScope(Map.of("scope", "classroom.membership.read"));
    }

    @Test
    void serviceScopeRejectsMalformedCollectionEntries() {
        assertInvalidServiceScope(List.of("classroom.membership.read", 42));
        assertInvalidServiceScope(Arrays.asList("classroom.membership.read", null));
        assertInvalidServiceScope(List.of(" "));
        assertInvalidServiceScope(List.of(""));
    }

    @Test
    void serviceWithValidScopeAndRolesIsRejected() {
        assertThrowsExactly(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(
                        "assessment-service",
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.SERVICE,
                                QuizopiaTokenClaims.SCOPE,
                                List.of("classroom.membership.read"),
                                QuizopiaTokenClaims.ROLES,
                                List.of("TEACHER")))));
    }

    @Test
    void malformedOrMixedPrincipalClaimsAreRejected() {
        assertThrows(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(
                        "not-a-uuid",
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("STUDENT")))));
        assertThrows(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(
                        "1-1-1-1-1",
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("STUDENT")))));
        assertThrows(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(
                        UUID.randomUUID().toString(),
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("STUDENT"),
                                QuizopiaTokenClaims.SCOPE,
                                List.of("service.read")))));
        assertThrows(InvalidBearerTokenException.class, () -> converter.convert(jwt("subject", Map.of())));
    }

    private void assertInvalidServiceScope(Object rawScope) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.SERVICE);
        claims.put(QuizopiaTokenClaims.SCOPE, rawScope);

        assertThrowsExactly(
                InvalidBearerTokenException.class, () -> converter.convert(jwt("assessment-service", claims)));
    }

    private static Jwt jwt(String subject, Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.putAll(additionalClaims);
        return Jwt.withTokenValue("redacted-test-token")
                .header("alg", "RS256")
                .claims(values -> values.putAll(claims))
                .issuedAt(Instant.parse("2026-09-13T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-13T00:05:00Z"))
                .build();
    }

    private static Set<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
    }
}
