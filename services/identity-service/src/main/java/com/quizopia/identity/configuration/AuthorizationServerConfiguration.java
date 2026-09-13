package com.quizopia.identity.configuration;

import com.quizopia.identity.persistence.repository.OAuth2ServiceClientRepository;
import com.quizopia.identity.security.authorization.IdentityRegisteredClientRepository;
import com.quizopia.identity.security.token.QuizopiaTokenClaims;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthorizationServerProperties.class)
@ConditionalOnProperty(
        prefix = "quizopia.identity.security.authorization-server",
        name = "enabled",
        havingValue = "true")
public class AuthorizationServerConfiguration {
    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthorizationServerProperties properties) {
        properties.validateRequiredValues();
        return AuthorizationServerSettings.builder()
                .issuer(properties.requiredIssuer())
                .build();
    }

    @Bean
    TokenSettings serviceClientTokenSettings(AuthorizationServerProperties properties) {
        properties.validateRequiredValues();
        Duration ttl = properties.requiredServiceAccessTokenTtl();
        return TokenSettings.builder()
                .accessTokenTimeToLive(ttl)
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(
            OAuth2ServiceClientRepository serviceClientRepository,
            TokenSettings serviceClientTokenSettings,
            PasswordEncoder serviceClientPasswordEncoder) {
        return new IdentityRegisteredClientRepository(
                serviceClientRepository, serviceClientTokenSettings, serviceClientPasswordEncoder);
    }

    @Bean
    OAuth2AuthorizationService authorizationService() {
        return new InMemoryOAuth2AuthorizationService();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> serviceAccessTokenCustomizer() {
        return context -> context.getClaims().claim(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.SERVICE);
    }

    @Bean
    OAuth2TokenGenerator<Jwt> serviceClientTokenGenerator(
            JwtEncoder jwtEncoder, OAuth2TokenCustomizer<JwtEncodingContext> serviceAccessTokenCustomizer) {
        JwtGenerator generator = new JwtGenerator(jwtEncoder);
        generator.setJwtCustomizer(serviceAccessTokenCustomizer);
        return generator;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            RegisteredClientRepository registeredClientRepository,
            OAuth2AuthorizationService authorizationService,
            AuthorizationServerSettings authorizationServerSettings,
            OAuth2TokenGenerator<Jwt> serviceClientTokenGenerator,
            PasswordEncoder serviceClientPasswordEncoder)
            throws Exception {
        RequestMatcher tokenEndpoint =
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, authorizationServerSettings.getTokenEndpoint());
        RequestMatcher jwkSetEndpoint =
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, authorizationServerSettings.getJwkSetEndpoint());

        http.securityMatcher(new OrRequestMatcher(tokenEndpoint, jwkSetEndpoint))
                .csrf(csrf -> csrf.disable())
                .oauth2AuthorizationServer(authorizationServer -> configureAuthorizationServer(
                        authorizationServer,
                        registeredClientRepository,
                        authorizationService,
                        authorizationServerSettings,
                        serviceClientTokenGenerator,
                        serviceClientPasswordEncoder))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    private static void configureAuthorizationServer(
            OAuth2AuthorizationServerConfigurer authorizationServer,
            RegisteredClientRepository registeredClientRepository,
            OAuth2AuthorizationService authorizationService,
            AuthorizationServerSettings authorizationServerSettings,
            OAuth2TokenGenerator<Jwt> serviceClientTokenGenerator,
            PasswordEncoder serviceClientPasswordEncoder) {
        authorizationServer
                .registeredClientRepository(registeredClientRepository)
                .authorizationService(authorizationService)
                .authorizationServerSettings(authorizationServerSettings)
                .tokenGenerator(serviceClientTokenGenerator)
                .clientAuthentication(clientAuthentication ->
                        clientAuthentication.authenticationProviders(providers -> providers.stream()
                                .filter(ClientSecretAuthenticationProvider.class::isInstance)
                                .map(ClientSecretAuthenticationProvider.class::cast)
                                .forEach(provider -> provider.setPasswordEncoder(serviceClientPasswordEncoder))));
    }
}
