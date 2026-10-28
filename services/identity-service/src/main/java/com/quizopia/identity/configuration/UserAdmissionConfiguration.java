package com.quizopia.identity.configuration;

import com.nimbusds.jose.jwk.RSAKey;
import com.quizopia.identity.api.admission.AdmissionErrors;
import com.quizopia.identity.security.admission.AdmissionTokenVerifier;
import com.quizopia.identity.security.token.QuizopiaJwtAuthenticationConverter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    UserAdmissionProperties.class,
    NoticeRecipientProperties.class,
    ProctoringStudentProperties.class,
    UserProfileProperties.class
})
public class UserAdmissionConfiguration {
    public static final String PATH = "/internal/security/user-admissions";
    public static final String SCOPE = "identity.user-admission";
    public static final String USER_HEADER = "X-Quizopia-User-Evidence";
    public static final String PROFILE_BATCH_PATH = "/internal/security/user-profiles/batch";
    public static final String PROFILE_SCOPE = "identity.user-profile.read";

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "${quizopia.identity.user-admission.enabled:false} or ${quizopia.identity.notice-recipients.enabled:false} or ${quizopia.identity.proctoring-students.enabled:false} or ${quizopia.identity.user-profiles.enabled:false}")
    AdmissionTokenVerifier admissionTokenVerifier(
            @Qualifier("identitySigningJwk") RSAKey key,
            AuthorizationServerProperties properties,
            QuizopiaJwtAuthenticationConverter converter)
            throws Exception {
        return new AdmissionTokenVerifier(key, properties, converter);
    }

    @Bean
    @Order(2)
    SecurityFilterChain userAdmissionChain(
            HttpSecurity http,
            UserAdmissionProperties properties,
            NoticeRecipientProperties recipients,
            ProctoringStudentProperties proctoring,
            UserProfileProperties profiles,
            org.springframework.core.env.Environment environment,
            ObjectProvider<AdmissionTokenVerifier> verifier,
            QuizopiaJwtAuthenticationConverter converter)
            throws Exception {
        properties.validate();
        if (environment.matchesProfiles("prod") && recipients.enabled() && !recipients.requireSecureTransport())
            throw new IllegalStateException("Private recipient HTTPS is required in production");
        if (environment.matchesProfiles("prod") && proctoring.enabled() && !proctoring.requireSecureTransport())
            throw new IllegalStateException("Private proctoring eligibility HTTPS is required in production");
        if (environment.matchesProfiles("prod") && profiles.enabled() && !profiles.requireSecureTransport())
            throw new IllegalStateException("Private user-profile HTTPS is required in production");
        http.securityMatcher("/internal/security/**")
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                                AdmissionErrors.write(response, 401, "SERVICE_AUTHENTICATION_INVALID"))
                        .accessDeniedHandler((request, response, exception) ->
                                AdmissionErrors.write(response, 403, "SERVICE_CALLER_FORBIDDEN")))
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            @Override
                            protected void doFilterInternal(
                                    HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                                    throws ServletException, IOException {
                                response.setHeader("Cache-Control", "no-store");
                                boolean recipient =
                                        request.getRequestURI().startsWith("/internal/security/notice-recipients/");
                                boolean target =
                                        request.getRequestURI().startsWith("/internal/security/proctoring-students/");
                                boolean profileBatch =
                                        request.getRequestURI().startsWith("/internal/security/user-profiles/");
                                boolean enabled = target
                                        ? proctoring.enabled()
                                        : recipient
                                                ? recipients.enabled()
                                                : profileBatch ? profiles.enabled() : properties.isEnabled();
                                boolean secure = target
                                        ? proctoring.requireSecureTransport()
                                        : recipient
                                                ? recipients.requireSecureTransport()
                                                : profileBatch
                                                        ? profiles.requireSecureTransport()
                                                        : properties.isRequireSecureTransport();
                                if (!enabled || (secure && !request.isSecure())) {
                                    AdmissionErrors.write(response, 503, "AUTHORITY_UNAVAILABLE");
                                    return;
                                }
                                chain.doFilter(request, response);
                            }
                        },
                        UsernamePasswordAuthenticationFilter.class);
        if (properties.isEnabled() || recipients.enabled() || proctoring.enabled() || profiles.enabled()) {
            http.oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint((request, response, exception) ->
                            AdmissionErrors.write(response, 401, "SERVICE_AUTHENTICATION_INVALID"))
                    .accessDeniedHandler((request, response, exception) ->
                            AdmissionErrors.write(response, 403, "SERVICE_CALLER_FORBIDDEN"))
                    .bearerTokenResolver(request -> {
                        if (Collections.list(request.getHeaders("Authorization"))
                                        .size()
                                != 1) throw new InvalidBearerTokenException("Invalid service credential presentation");
                        return new DefaultBearerTokenResolver().resolve(request);
                    })
                    .jwt(jwt -> jwt.decoder(verifier.getObject().decoder()).jwtAuthenticationConverter(converter)));
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().access((authentication, context) -> {
                var caller = authentication.get();
                var authorities =
                        caller.getAuthorities().stream().map(Object::toString).toList();
                var request = context.getRequest();
                String path = request.getRequestURI();
                boolean admission = "POST".equals(request.getMethod())
                        && PATH.equals(path)
                        && properties.isEnabled()
                        && authorities.contains("SCOPE_" + SCOPE)
                        && properties.getAllowedClients().contains(caller.getName());
                boolean recipient = "POST".equals(request.getMethod())
                        && recipients.enabled()
                        && path.matches(
                                "/internal/security/notice-recipients/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/eligibility")
                        && authorities.contains("SCOPE_identity.notice-recipient.read")
                        && recipients.assessmentClientId().equals(caller.getName());
                boolean target = "POST".equals(request.getMethod())
                        && proctoring.enabled()
                        && path.matches(
                                "/internal/security/proctoring-students/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/eligibility")
                        && authorities.contains("SCOPE_identity.proctoring-student.read")
                        && proctoring.assessmentClientId().equals(caller.getName());
                boolean profileBatch = "POST".equals(request.getMethod())
                        && profiles.enabled()
                        && PROFILE_BATCH_PATH.equals(path)
                        && authorities.contains("SCOPE_" + PROFILE_SCOPE)
                        && profiles.assessmentClientId().equals(caller.getName());
                return new AuthorizationDecision(caller.isAuthenticated()
                        && authorities.contains("TOKEN_SERVICE")
                        && (admission || recipient || target || profileBatch));
            }));
        } else http.authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll());
        return http.build();
    }
}
