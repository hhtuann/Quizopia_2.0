package com.quizopia.identity.security.token;

import com.nimbusds.jose.jwk.RSAKey;
import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.configuration.AuthorizationServerProperties;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
        prefix = "quizopia.identity.security.authorization-server",
        name = "enabled",
        havingValue = "true")
public class UserAccessTokenIssuer {
    private final UserAccountRepository userAccountRepository;
    private final UserRoleRepository userRoleRepository;
    private final JwtEncoder jwtEncoder;
    private final AuthorizationServerProperties properties;
    private final Clock clock;
    private final String signingKeyId;

    public UserAccessTokenIssuer(
            UserAccountRepository userAccountRepository,
            UserRoleRepository userRoleRepository,
            JwtEncoder jwtEncoder,
            AuthorizationServerProperties properties,
            Clock clock,
            @Qualifier("identitySigningJwk") RSAKey signingJwk) {
        this.userAccountRepository = userAccountRepository;
        this.userRoleRepository = userRoleRepository;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
        this.signingKeyId = signingJwk.getKeyID();
    }

    @Transactional(readOnly = true)
    public IssuedUserAccessToken issue(UUID authenticatedUserId) {
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId");
        UserAccountEntity user =
                userAccountRepository.findById(authenticatedUserId).orElseThrow(UserAccessTokenIssuanceException::new);
        if (!AccountLifecycleStatus.ACTIVE.equals(user.getAccountStatus()) || user.getEmailVerifiedAt() == null) {
            throw new UserAccessTokenIssuanceException();
        }

        List<String> roles = userRoleRepository.findAllByUser_Id(user.getId()).stream()
                .map(role -> role.getRole().name())
                .distinct()
                .sorted()
                .toList();
        if (!roles.contains(UserRole.STUDENT.name())) {
            throw new UserAccessTokenIssuanceException();
        }

        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.requiredUserAccessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.requiredIssuer())
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.USER)
                .claim(QuizopiaTokenClaims.ROLES, roles)
                .build();
        JwsHeader header =
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKeyId).build();
        String tokenValue =
                jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedUserAccessToken(tokenValue, issuedAt, expiresAt);
    }
}
