package com.quizopia.classroom.configuration;

import com.quizopia.classroom.security.ClassroomSecurityErrorHandler;
import com.quizopia.classroom.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.classroom.security.QuizopiaTokenClaims;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            QuizopiaJwtAuthenticationConverter jwtAuthenticationConverter,
            ClassroomSecurityErrorHandler securityErrorHandler)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/classrooms")
                        .hasAllAuthorities(QuizopiaTokenClaims.USER_AUTHORITY, "ROLE_TEACHER")
                        .requestMatchers(HttpMethod.GET, "/classrooms/*")
                        .hasAuthority(QuizopiaTokenClaims.USER_AUTHORITY)
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
