package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import com.quizopia.quiz.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.quiz.security.QuizopiaTokenClaims;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class QuizopiaJwtAuthenticationConverterTest {
    private static final String USER_ID = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

    private final QuizopiaJwtAuthenticationConverter converter = new QuizopiaJwtAuthenticationConverter();

    @ParameterizedTest
    @MethodSource("validUserRoles")
    void validUserRolesMapToOnlyTheAuthoritiesActuallyPresent(List<String> roles, Set<String> expectedAuthorities) {
        AbstractAuthenticationToken authentication = converter.convert(jwt(
                USER_ID,
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.USER,
                        QuizopiaTokenClaims.ROLES,
                        roles)));

        assertEquals(USER_ID, authentication.getName());
        assertEquals(expectedAuthorities, authorities(authentication));
        assertFalse(authorities(authentication).contains(QuizopiaTokenClaims.SERVICE_AUTHORITY));
        assertFalse(authorities(authentication).stream().anyMatch(authority -> authority.startsWith("SCOPE_")));
    }

    @Test
    void validServiceScopesMapToExactServiceAuthoritiesWithoutUserOrRoleAuthorities() {
        AbstractAuthenticationToken authentication = converter.convert(jwt(
                "assessment-service",
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.SERVICE,
                        QuizopiaTokenClaims.SCOPE,
                        List.of("quiz.snapshot.read", "quiz.version.read"))));

        assertEquals("assessment-service", authentication.getName());
        assertEquals(
                Set.of("TOKEN_SERVICE", "SCOPE_quiz.snapshot.read", "SCOPE_quiz.version.read"),
                authorities(authentication));
        assertFalse(authorities(authentication).contains(QuizopiaTokenClaims.USER_AUTHORITY));
        assertFalse(authorities(authentication).stream().anyMatch(authority -> authority.startsWith("ROLE_")));
    }

    @Test
    void missingUnknownAndWrongTypePrincipalDiscriminatorsRejectTheCompleteToken() {
        assertInvalid(jwt(USER_ID, Map.of(QuizopiaTokenClaims.ROLES, List.of("TEACHER"))));
        assertInvalid(jwt(
                USER_ID,
                Map.of(QuizopiaTokenClaims.PRINCIPAL_TYPE, "user", QuizopiaTokenClaims.ROLES, List.of("TEACHER"))));
        assertInvalid(jwt(
                USER_ID,
                Map.of(QuizopiaTokenClaims.PRINCIPAL_TYPE, 42, QuizopiaTokenClaims.ROLES, List.of("TEACHER"))));
    }

    @Test
    void userRequiresRolesAsACollection() {
        assertInvalidUserRoles(null);
        assertInvalidUserRoles("TEACHER");
        assertInvalidUserRoles(Map.of("role", "TEACHER"));
        assertInvalidUserRoles(42);
    }

    @Test
    void userRejectsUnknownNullAndNonStringRoles() {
        assertInvalidUserRoles(List.of("OWNER"));
        assertInvalidUserRoles(Arrays.asList("TEACHER", null));
        assertInvalidUserRoles(List.of("TEACHER", 42));
        assertInvalidUserRoles(List.of("teacher"));
    }

    @Test
    void userRejectsAnyScopeClaimInsteadOfMixingAuthorities() {
        assertInvalid(jwt(
                USER_ID,
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.USER,
                        QuizopiaTokenClaims.ROLES,
                        List.of("TEACHER"),
                        QuizopiaTokenClaims.SCOPE,
                        List.of("quiz.write"))));
    }

    @Test
    void userRejectsMissingBlankNonUuidAndNoncanonicalSubjects() {
        assertInvalidUserSubject(null);
        assertInvalidUserSubject("");
        assertInvalidUserSubject(" ");
        assertInvalidUserSubject("not-a-uuid");
        assertInvalidUserSubject("1-1-1-1-1");
        assertInvalidUserSubject(USER_ID.toUpperCase());
    }

    @Test
    void serviceRequiresScopeAsANonEmptyCollection() {
        assertInvalidServiceScope(null);
        assertInvalidServiceScope(List.of());
        assertInvalidServiceScope("quiz.read");
        assertInvalidServiceScope(Map.of("scope", "quiz.read"));
        assertInvalidServiceScope(42);
    }

    @Test
    void serviceRejectsNullNonStringEmptyAndBlankScopeElements() {
        assertInvalidServiceScope(Arrays.asList("quiz.read", null));
        assertInvalidServiceScope(List.of("quiz.read", 42));
        assertInvalidServiceScope(List.of(""));
        assertInvalidServiceScope(List.of(" \t"));
    }

    @Test
    void serviceRejectsRolesInsteadOfMixingAuthorities() {
        assertInvalid(jwt(
                "assessment-service",
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.SERVICE,
                        QuizopiaTokenClaims.SCOPE,
                        List.of("quiz.read"),
                        QuizopiaTokenClaims.ROLES,
                        List.of("TEACHER"))));
    }

    @Test
    void serviceRejectsMissingAndBlankSubjects() {
        assertInvalidServiceSubject(null);
        assertInvalidServiceSubject("");
        assertInvalidServiceSubject(" \t");
    }

    private static Stream<Arguments> validUserRoles() {
        return Stream.of(
                Arguments.of(List.of("STUDENT"), Set.of("TOKEN_USER", "ROLE_STUDENT")),
                Arguments.of(List.of("TEACHER"), Set.of("TOKEN_USER", "ROLE_TEACHER")),
                Arguments.of(List.of("STUDENT", "TEACHER"), Set.of("TOKEN_USER", "ROLE_STUDENT", "ROLE_TEACHER")),
                Arguments.of(List.of("ADMIN"), Set.of("TOKEN_USER", "ROLE_ADMIN")),
                Arguments.of(List.of("ADMIN", "TEACHER"), Set.of("TOKEN_USER", "ROLE_ADMIN", "ROLE_TEACHER")));
    }

    private void assertInvalidUserRoles(Object roles) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.USER);
        if (roles != null) {
            claims.put(QuizopiaTokenClaims.ROLES, roles);
        }
        assertInvalid(jwt(USER_ID, claims));
    }

    private void assertInvalidUserSubject(String subject) {
        assertInvalid(jwt(
                subject,
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.USER,
                        QuizopiaTokenClaims.ROLES,
                        List.of("TEACHER"))));
    }

    private void assertInvalidServiceScope(Object scope) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.SERVICE);
        if (scope != null) {
            claims.put(QuizopiaTokenClaims.SCOPE, scope);
        }
        assertInvalid(jwt("assessment-service", claims));
    }

    private void assertInvalidServiceSubject(String subject) {
        assertInvalid(jwt(
                subject,
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.SERVICE,
                        QuizopiaTokenClaims.SCOPE,
                        List.of("quiz.read"))));
    }

    private void assertInvalid(Jwt jwt) {
        assertThrowsExactly(InvalidBearerTokenException.class, () -> converter.convert(jwt));
    }

    private static Jwt jwt(String subject, Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new LinkedHashMap<>();
        if (subject != null) {
            claims.put("sub", subject);
        }
        claims.putAll(additionalClaims);
        return Jwt.withTokenValue("redacted-test-token")
                .header("alg", "RS256")
                .claims(values -> values.putAll(claims))
                .issuedAt(Instant.parse("2026-09-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-26T00:05:00Z"))
                .build();
    }

    private static Set<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
    }
}
