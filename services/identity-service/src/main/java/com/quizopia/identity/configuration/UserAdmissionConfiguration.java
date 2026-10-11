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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@EnableConfigurationProperties(UserAdmissionProperties.class)
public class UserAdmissionConfiguration {
    public static final String PATH = "/internal/security/user-admissions";
    public static final String SCOPE = "identity.user-admission";
    public static final String USER_HEADER = "X-Quizopia-User-Evidence";

    @Bean
    @ConditionalOnProperty(prefix = "quizopia.identity.user-admission", name = "enabled", havingValue = "true")
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
            ObjectProvider<AdmissionTokenVerifier> verifier,
            QuizopiaJwtAuthenticationConverter converter)
            throws Exception {
        properties.validate();
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
                                if (!properties.isEnabled()
                                        || (properties.isRequireSecureTransport() && !request.isSecure())) {
                                    AdmissionErrors.write(response, 503, "AUTHORITY_UNAVAILABLE");
                                    return;
                                }
                                chain.doFilter(request, response);
                            }
                        },
                        UsernamePasswordAuthenticationFilter.class);
        if (properties.isEnabled()) {
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
                return new AuthorizationDecision(caller.isAuthenticated()
                        && authorities.contains("TOKEN_SERVICE")
                        && authorities.contains("SCOPE_" + SCOPE)
                        && properties.getAllowedClients().contains(caller.getName()));
            }));
        } else http.authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll());
        return http.build();
    }
}
