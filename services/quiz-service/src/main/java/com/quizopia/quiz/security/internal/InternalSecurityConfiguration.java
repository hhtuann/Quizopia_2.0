package com.quizopia.quiz.security.internal;

import com.quizopia.quiz.security.*;
import com.quizopia.quiz.security.admission.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableConfigurationProperties(InternalAuthorityProperties.class)
public class InternalSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain internalSecurity(
            HttpSecurity http,
            InternalAuthorityProperties config,
            JwtDecoder decoder,
            QuizopiaJwtAuthenticationConverter converter,
            IdentityAdmissionClient admission,
            AdmissionProperties bounds)
            throws Exception {
        return http.securityMatcher("/internal/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().access((auth, context) -> {
                    var caller = auth.get();
                    String scope = InternalAuthorityFilter.scope(
                            context.getRequest().getMethod(),
                            context.getRequest().getRequestURI());
                    var authorities = caller.getAuthorities().stream()
                            .map(Object::toString)
                            .toList();
                    return new AuthorizationDecision(scope != null
                            && caller.isAuthenticated()
                            && authorities.contains("TOKEN_SERVICE")
                            && authorities.contains("SCOPE_" + scope)
                            && java.util.Objects.equals(config.getAssessmentClientId(), caller.getName()));
                }))
                .exceptionHandling(e -> e.authenticationEntryPoint(
                                (q, r, x) -> InternalErrors.write(r, 401, "SERVICE_AUTHENTICATION_INVALID"))
                        .accessDeniedHandler((q, r, x) -> InternalErrors.write(r, 403, "SERVICE_CALLER_FORBIDDEN")))
                .oauth2ResourceServer(o -> o.authenticationEntryPoint(
                                (q, r, x) -> InternalErrors.write(r, 401, "SERVICE_AUTHENTICATION_INVALID"))
                        .accessDeniedHandler((q, r, x) -> InternalErrors.write(r, 403, "SERVICE_CALLER_FORBIDDEN"))
                        .jwt(j -> j.jwtAuthenticationConverter(converter)))
                .addFilterAfter(
                        new InternalAuthorityFilter(config, decoder, converter, admission, bounds),
                        AuthorizationFilter.class)
                .build();
    }
}
