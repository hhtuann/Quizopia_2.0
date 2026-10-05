package com.quizopia.identity.configuration;

import com.quizopia.identity.api.auth.AuthController;
import com.quizopia.identity.api.auth.IdentityAccessDeniedHandler;
import com.quizopia.identity.security.token.QuizopiaJwtAuthenticationConverter;
import com.quizopia.identity.security.token.QuizopiaTokenClaims;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(TrustedBrowserOriginProperties.class)
public class SecurityConfiguration {
    private static RequestMatcher publicAuthEndpoints() {
        return new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthController.REGISTER_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthController.LOGIN_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthController.REFRESH_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthController.LOGOUT_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthController.REQUEST_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthController.CONFIRM_PATH));
    }

    @Bean
    @Order(1)
    SecurityFilterChain publicAuthSecurityFilterChain(
            HttpSecurity http,
            TrustedCookieAuthOriginFilter trustedCookieAuthOriginFilter,
            IdentityAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http.securityMatcher(publicAuthEndpoints())
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(trustedCookieAuthOriginFilter, AuthorizationFilter.class)
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            QuizopiaJwtAuthenticationConverter jwtAuthenticationConverter,
            TrustedCookieAuthOriginFilter trustedCookieAuthOriginFilter,
            IdentityAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .addFilterBefore(trustedCookieAuthOriginFilter, AuthorizationFilter.class)
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, AuthController.ME_PATH))
                        .hasAuthority(QuizopiaTokenClaims.USER_AUTHORITY)
                        .requestMatchers(PathPatternRequestMatcher.pathPattern(
                                HttpMethod.POST, AuthController.TEACHER_ENABLEMENT_PATH))
                        .hasAuthority(QuizopiaTokenClaims.USER_AUTHORITY)
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
