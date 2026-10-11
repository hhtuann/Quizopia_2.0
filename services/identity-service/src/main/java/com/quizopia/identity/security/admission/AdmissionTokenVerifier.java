package com.quizopia.identity.security.admission;

import com.nimbusds.jose.jwk.RSAKey;
import com.quizopia.identity.application.admission.AdmissionFailure;
import com.quizopia.identity.configuration.AuthorizationServerProperties;
import com.quizopia.identity.security.token.QuizopiaJwtAuthenticationConverter;
import com.quizopia.identity.security.token.QuizopiaTokenClaims;
import java.time.Clock;
import java.time.Duration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class AdmissionTokenVerifier {
    private final NimbusJwtDecoder decoder;
    private final QuizopiaJwtAuthenticationConverter converter;

    public AdmissionTokenVerifier(
            RSAKey key, AuthorizationServerProperties properties, QuizopiaJwtAuthenticationConverter converter)
            throws Exception {
        if (!properties.isEnabled()) throw new IllegalStateException("Identity issuance must be enabled for admission");
        this.converter = converter;
        decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.requiredIssuer()), new JwtTimestampValidator(Duration.ZERO)));
    }

    public JwtDecoder decoder() {
        return token -> {
            Jwt jwt = decoder.decode(token);
            if (jwt.getIssuedAt() == null
                    || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || jwt.getIssuedAt().isAfter(Clock.systemUTC().instant()))
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid token timestamps");
            return jwt;
        };
    }

    public Jwt user(String evidence) {
        try {
            Jwt jwt = decoder().decode(evidence);
            converter.convert(jwt);
            if (!QuizopiaTokenClaims.USER.equals(jwt.getClaimAsString(QuizopiaTokenClaims.PRINCIPAL_TYPE)))
                throw AdmissionFailure.invalidUser();
            return jwt;
        } catch (RuntimeException exception) {
            throw AdmissionFailure.invalidUser();
        }
    }
}
