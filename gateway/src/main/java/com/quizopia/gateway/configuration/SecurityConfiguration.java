package com.quizopia.gateway.configuration;

import com.quizopia.gateway.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.gateway.security.QuizopiaTokenClaims;
import com.quizopia.gateway.security.admission.AdmissionRoutes;
import com.quizopia.gateway.security.admission.IdentityAdmissionClient;
import com.quizopia.gateway.security.admission.StudentAttemptSecurityErrors;
import com.quizopia.gateway.security.admission.UserAdmissionWebFilter;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewayBrowserOriginProperties.class)
public class SecurityConfiguration {
    private static final String REGISTER_PATH = "/api/auth/register";
    private static final String VERIFICATION_REQUEST_PATH = "/api/auth/email-verification/request";
    private static final String VERIFICATION_CONFIRM_PATH = "/api/auth/email-verification/confirm";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REFRESH_PATH = "/api/auth/refresh";
    private static final String LOGOUT_PATH = "/api/auth/logout";
    private static final String ME_PATH = "/api/auth/me";

    @Bean
    @Order(0)
    SecurityWebFilterChain publicAuthSecurityWebFilterChain(
            ServerHttpSecurity http, CorsConfigurationSource corsConfigurationSource) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(
                        HttpMethod.POST,
                        REGISTER_PATH,
                        VERIFICATION_REQUEST_PATH,
                        VERIFICATION_CONFIRM_PATH,
                        LOGIN_PATH,
                        REFRESH_PATH,
                        LOGOUT_PATH))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    @Bean
    @Order(1)
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            QuizopiaJwtAuthenticationConverter jwtAuthenticationConverter,
            IdentityAdmissionClient admissionClient,
            com.quizopia.gateway.security.admission.PublicationProperties publication,
            CorsConfigurationSource corsConfigurationSource,
            com.quizopia.gateway.security.admission.AttemptFeatureProperties attemptFeatures,
            com.quizopia.gateway.security.admission.InboxProperties inbox,
            @org.springframework.beans.factory.annotation.Value("${quizopia.proctoring.enabled:false}")
                    boolean proctoringEnabled,
            @org.springframework.beans.factory.annotation.Value("${quizopia.proctoring.media.enabled:false}")
                    boolean mediaEnabled) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, ME_PATH)
                        .hasAuthority(QuizopiaTokenClaims.USER_AUTHORITY)
                        .anyExchange()
                        .access((authentication, context) -> {
                            String path =
                                    context.getExchange().getRequest().getURI().getRawPath();
                            String role = AdmissionRoutes.requiredRole(
                                    context.getExchange()
                                            .getRequest()
                                            .getMethod()
                                            .name(),
                                    path);
                            if (role == null && !AdmissionRoutes.closed(path)) {
                                return org.springframework.security.authorization
                                        .AuthenticatedReactiveAuthorizationManager.authenticated()
                                        .authorize(authentication, context);
                            }
                            return authentication
                                    .<org.springframework.security.authorization.AuthorizationResult>map(caller -> {
                                        var authorities = caller.getAuthorities().stream()
                                                .map(Object::toString)
                                                .toList();
                                        return new AuthorizationDecision(caller.isAuthenticated()
                                                && (role != null
                                                        ? authorities.contains("TOKEN_USER")
                                                                && authorities.contains("ROLE_" + role)
                                                        : !AdmissionRoutes.closed(path)));
                                    })
                                    .defaultIfEmpty(new AuthorizationDecision(false));
                        }))
                .addFilterAfter(
                        new UserAdmissionWebFilter(
                                admissionClient,
                                publication,
                                attemptFeatures,
                                inbox.inboxEnabled(),
                                proctoringEnabled,
                                mediaEnabled),
                        SecurityWebFiltersOrder.AUTHORIZATION)
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(StudentAttemptSecurityErrors.unauthenticated())
                                .accessDeniedHandler(StudentAttemptSecurityErrors.denied()))
                .oauth2ResourceServer(
                        oauth2 -> oauth2.authenticationEntryPoint(StudentAttemptSecurityErrors.unauthenticated())
                                .accessDeniedHandler(StudentAttemptSecurityErrors.denied())
                                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayBrowserOriginProperties browserOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(browserOrigins.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION, "Idempotency-Key"));
        cors.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
