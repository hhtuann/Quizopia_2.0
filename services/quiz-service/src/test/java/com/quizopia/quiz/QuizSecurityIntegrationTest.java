package com.quizopia.quiz;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quizopia.quiz.configuration.SecurityConfiguration;
import com.quizopia.quiz.security.QuizSecurityErrorHandler;
import com.quizopia.quiz.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.quiz.security.QuizopiaTokenClaims;
import com.quizopia.quiz.security.TeacherAuthoringPrincipalResolver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = QuizSecurityIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuizSecurityIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    JwtDecoder jwtDecoder;

    @BeforeEach
    void resetDecoder() {
        reset(jwtDecoder);
    }

    @Test
    void technicalHealthEndpointRemainsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void missingTokenReturnsMachineReadable401() throws Exception {
        mvc.perform(get("/security-test/principal"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/security-test/principal"));
    }

    @Test
    void resourceServerUsesStrictConverterAndRejectsMixedClaimsWithoutExposingThem() throws Exception {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("mixed-token"))
                .thenReturn(jwt(
                        userId.toString(),
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("TEACHER"),
                                QuizopiaTokenClaims.SCOPE,
                                List.of("sensitive.quiz.write"))));

        mvc.perform(get("/security-test/principal").header("Authorization", "Bearer mixed-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(content().string(not(containsString("mixed-token"))))
                .andExpect(content().string(not(containsString("sensitive.quiz.write"))))
                .andExpect(content().string(not(containsString(userId.toString()))));
    }

    @Test
    void installedConverterMapsValidTeacherUserAndAuthoringBoundaryReturnsItsSubject() throws Exception {
        UUID userId = UUID.randomUUID();
        configureUserToken("teacher-token", userId, List.of("STUDENT", "TEACHER"));

        mvc.perform(get("/security-test/principal").header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(userId.toString()))
                .andExpect(jsonPath("$.authorities", hasSize(3)))
                .andExpect(jsonPath("$.authorities", containsInAnyOrder("TOKEN_USER", "ROLE_STUDENT", "ROLE_TEACHER")));
        mvc.perform(get("/security-test/author").header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(userId.toString()));
    }

    @Test
    void validServiceTokenIsAuthenticatedButTeacherAuthoringBoundaryReturns403() throws Exception {
        when(jwtDecoder.decode("service-token"))
                .thenReturn(jwt(
                        "assessment-service",
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.SERVICE,
                                QuizopiaTokenClaims.SCOPE,
                                List.of("quiz.teacher.write"))));

        mvc.perform(get("/security-test/principal").header("Authorization", "Bearer service-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities", hasSize(2)))
                .andExpect(jsonPath("$.authorities", containsInAnyOrder("TOKEN_SERVICE", "SCOPE_quiz.teacher.write")));
        mvc.perform(get("/security-test/author").header("Authorization", "Bearer service-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Access is denied"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.path").value("/security-test/author"))
                .andExpect(content().string(not(containsString("assessment-service"))))
                .andExpect(content().string(not(containsString("quiz.teacher.write"))));
    }

    private void configureUserToken(String token, UUID userId, List<String> roles) {
        when(jwtDecoder.decode(token))
                .thenReturn(jwt(
                        userId.toString(),
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                roles)));
    }

    private static Jwt jwt(String subject, Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.putAll(additionalClaims);
        return Jwt.withTokenValue("redacted-test-token")
                .header("alg", "RS256")
                .claims(values -> values.putAll(claims))
                .issuedAt(Instant.parse("2026-09-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-26T00:05:00Z"))
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                FlywayAutoConfiguration.class
            })
    @Import({
        SecurityTestController.class,
        TeacherAuthoringPrincipalResolver.class,
        QuizopiaJwtAuthenticationConverter.class,
        QuizSecurityErrorHandler.class,
        SecurityConfiguration.class
    })
    static class TestApplication {
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    @RestController
    static class SecurityTestController {
        private final TeacherAuthoringPrincipalResolver teacherAuthoringPrincipalResolver;

        SecurityTestController(TeacherAuthoringPrincipalResolver teacherAuthoringPrincipalResolver) {
            this.teacherAuthoringPrincipalResolver = teacherAuthoringPrincipalResolver;
        }

        @GetMapping("/security-test/principal")
        Map<String, Object> principal(Authentication authentication) {
            return Map.of(
                    "name",
                    authentication.getName(),
                    "authorities",
                    authentication.getAuthorities().stream()
                            .map(authority -> authority.getAuthority())
                            .toList());
        }

        @GetMapping("/security-test/author")
        String author(Authentication authentication) {
            return teacherAuthoringPrincipalResolver.resolve(authentication).toString();
        }
    }
}
