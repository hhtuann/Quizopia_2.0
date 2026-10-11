package com.quizopia.assessment;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

final class AdmissionTestTokens {
    static final String ISSUER = "https://identity.admission.test";
    static final String ACTOR = "00000000-0000-0000-0000-000000000001";
    static final RSAKey KEY = key();

    static RSAKey key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("admission-http")
                    .build();
        } catch (Exception error) {
            throw new IllegalStateException("Fixture key generation failed");
        }
    }

    static String user() {
        return sign(
                KEY,
                ISSUER,
                ACTOR,
                "USER",
                Map.of("roles", java.util.List.of("STUDENT", "TEACHER")),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    static String sign(
            RSAKey key,
            String issuer,
            String subject,
            String type,
            Map<String, Object> fields,
            Instant issued,
            Instant expiry) {
        var builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(issued)
                .expiresAt(expiry)
                .claim("principal_type", type);
        fields.forEach(builder::claim);
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256)
                                .keyId(key.getKeyID())
                                .build(),
                        builder.build()))
                .getTokenValue();
    }
}
