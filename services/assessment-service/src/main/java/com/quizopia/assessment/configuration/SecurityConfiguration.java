package com.quizopia.assessment.configuration;

import com.quizopia.assessment.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.assessment.security.admission.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@org.springframework.boot.context.properties.EnableConfigurationProperties(ProctoringAuthorityProperties.class)
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            QuizopiaJwtAuthenticationConverter converter,
            Environment environment,
            com.quizopia.assessment.security.SecurityErrorHandler errors,
            IdentityAdmissionClient admission,
            AdmissionProperties properties,
            PublicationProperties publication,
            AssessmentFeatureProperties features,
            ClosureProperties closure,
            ProctoringAuthorityProperties proctoringAuthority)
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
                        .anyRequest()
                        .access((authentication, context) -> {
                            if (com.quizopia.assessment.security.proctoring.ProctoringAuthorityRoutes.matches(
                                    context.getRequest().getMethod(),
                                    context.getRequest().getRequestURI()))
                                return new AuthorizationDecision(
                                        com.quizopia.assessment.security.proctoring.ProctoringAuthorityRoutes.permitted(
                                                authentication.get(), proctoringAuthority));
                            var role = PublicationRoutes.requiredRole(
                                    context.getRequest().getMethod(),
                                    context.getRequest().getRequestURI());
                            var auth = authentication.get();
                            var authorities = auth.getAuthorities().stream()
                                    .map(Object::toString)
                                    .toList();
                            return new AuthorizationDecision(role != null
                                    && auth.isAuthenticated()
                                    && authorities.contains("TOKEN_USER")
                                    && authorities.contains("ROLE_" + role));
                        }))
                .addFilterAfter(
                        new UserAdmissionFilter(admission, properties, publication, features, closure.inboxEnabled()),
                        AuthorizationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
}
