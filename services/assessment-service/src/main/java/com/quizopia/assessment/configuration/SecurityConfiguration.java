package com.quizopia.assessment.configuration;

import com.quizopia.assessment.security.QuizopiaJwtAuthenticationConverter;
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
            com.quizopia.assessment.security.SecurityErrorHandler errors)
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
                        .denyAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
}
