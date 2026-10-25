package com.quizopia.classroom.configuration;

import com.quizopia.classroom.security.ClassroomSecurityErrorHandler;
import com.quizopia.classroom.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.classroom.security.QuizopiaTokenClaims;
import com.quizopia.classroom.security.admission.AdmissionProperties;
import com.quizopia.classroom.security.admission.IdentityAdmissionClient;
import com.quizopia.classroom.security.admission.UserAdmissionFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            QuizopiaJwtAuthenticationConverter jwtAuthenticationConverter,
            ClassroomSecurityErrorHandler securityErrorHandler,
            IdentityAdmissionClient admissionClient,
            AdmissionProperties admissionProperties,
            Environment environment)
            throws Exception {
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(request -> !environment.matchesProfiles("prod")
                                && "GET".equals(request.getMethod())
                                && (request.getRequestURI().equals("/v3/api-docs")
                                        || request.getRequestURI().startsWith("/v3/api-docs/")
                                        || request.getRequestURI().startsWith("/swagger-ui/")))
                        .permitAll()
                        .requestMatchers(
                                request -> ("POST".equals(request.getMethod()) || "GET".equals(request.getMethod()))
                                        && "/classrooms".equals(request.getRequestURI()))
                        .hasAllAuthorities(QuizopiaTokenClaims.USER_AUTHORITY, "ROLE_TEACHER")
                        .requestMatchers(request ->
                                UserAdmissionFilter.requiredRole(request.getMethod(), request.getRequestURI()) != null)
                        .hasAuthority(QuizopiaTokenClaims.USER_AUTHORITY)
                        .anyRequest()
                        .denyAll())
                .addFilterAfter(
                        new UserAdmissionFilter(admissionClient, admissionProperties), AuthorizationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
