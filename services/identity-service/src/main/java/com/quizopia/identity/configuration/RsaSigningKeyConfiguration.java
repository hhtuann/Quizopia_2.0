package com.quizopia.identity.configuration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RsaSigningKeyProperties.class)
@ConditionalOnProperty(prefix = "quizopia.identity.security.signing-key", name = "private-key-path")
public class RsaSigningKeyConfiguration {
    @Bean
    RsaSigningKeyLoader rsaSigningKeyLoader() {
        return new RsaSigningKeyLoader();
    }

    @Bean(name = "identitySigningJwk")
    RSAKey identitySigningJwk(RsaSigningKeyLoader loader, RsaSigningKeyProperties properties) {
        return loader.load(properties);
    }

    @Bean(name = "identityPublicJwkSet")
    JWKSet identityPublicJwkSet(@Qualifier("identitySigningJwk") RSAKey identitySigningJwk) {
        return new JWKSet(identitySigningJwk.toPublicJWK());
    }

    @Bean(name = "jwkSource")
    JWKSource<SecurityContext> identityPublicJwkSource(@Qualifier("identityPublicJwkSet") JWKSet identityPublicJwkSet) {
        return new ImmutableJWKSet<>(identityPublicJwkSet);
    }

    @Bean
    JwtEncoder identityJwtEncoder(@Qualifier("identitySigningJwk") RSAKey identitySigningJwk) {
        JWKSource<SecurityContext> privateSigningSource = new ImmutableJWKSet<>(new JWKSet(identitySigningJwk));
        return new NimbusJwtEncoder(privateSigningSource);
    }
}
