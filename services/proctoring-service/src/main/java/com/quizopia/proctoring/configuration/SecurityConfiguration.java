package com.quizopia.proctoring.configuration;

import com.quizopia.proctoring.security.QuizopiaJwtAuthenticationConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            QuizopiaJwtAuthenticationConverter converter,
            Environment environment,
            com.quizopia.proctoring.security.SecurityErrorHandler errors,
            com.quizopia.proctoring.security.admission.IdentityAdmissionClient admission,
            com.quizopia.proctoring.security.admission.AdmissionProperties bounds,
            MonitoringProperties monitoring,
            com.quizopia.proctoring.media.MediaProperties media)
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
                            String role = com.quizopia.proctoring.security.admission.ProctoringRoutes.role(
                                    context.getRequest().getMethod(),
                                    context.getRequest().getRequestURI());
                            var caller = authentication.get();
                            var authorities = caller.getAuthorities().stream()
                                    .map(Object::toString)
                                    .toList();
                            return new org.springframework.security.authorization.AuthorizationDecision(role != null
                                    && caller.isAuthenticated()
                                    && authorities.contains("TOKEN_USER")
                                    && authorities.contains("ROLE_" + role));
                        }))
                .addFilterAfter(
                        new com.quizopia.proctoring.security.admission.MonitoringAdmissionFilter(
                                admission, bounds, monitoring, media),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
}
