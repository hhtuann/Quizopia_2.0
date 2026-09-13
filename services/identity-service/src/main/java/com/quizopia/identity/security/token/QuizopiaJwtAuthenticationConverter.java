package com.quizopia.identity.security.token;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class QuizopiaJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private static final Set<String> GLOBAL_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Object principalType = jwt.getClaims().get(QuizopiaTokenClaims.PRINCIPAL_TYPE);
        if (QuizopiaTokenClaims.USER.equals(principalType)) {
            return userAuthentication(jwt);
        }
        if (QuizopiaTokenClaims.SERVICE.equals(principalType)) {
            return serviceAuthentication(jwt);
        }
        throw invalidToken();
    }

    private AbstractAuthenticationToken userAuthentication(Jwt jwt) {
        requireUuidSubject(jwt);
        if (jwt.hasClaim(QuizopiaTokenClaims.SCOPE)) {
            throw invalidToken();
        }

        Object rolesClaim = jwt.getClaims().get(QuizopiaTokenClaims.ROLES);
        if (!(rolesClaim instanceof Collection<?> roles)) {
            throw invalidToken();
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(QuizopiaTokenClaims.USER_AUTHORITY));
        for (Object rawRole : roles) {
            if (!(rawRole instanceof String role) || !GLOBAL_ROLES.contains(role)) {
                throw invalidToken();
            }
            authorities.add(new SimpleGrantedAuthority(QuizopiaTokenClaims.ROLE_AUTHORITY_PREFIX + role));
        }
        return new JwtAuthenticationToken(jwt, List.copyOf(authorities), jwt.getSubject());
    }

    private AbstractAuthenticationToken serviceAuthentication(Jwt jwt) {
        if (jwt.hasClaim(QuizopiaTokenClaims.ROLES)) {
            throw invalidToken();
        }
        requireSubject(jwt);
        List<String> scopes = requireServiceScopes(jwt);

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(QuizopiaTokenClaims.SERVICE_AUTHORITY));
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority(QuizopiaTokenClaims.SCOPE_AUTHORITY_PREFIX + scope));
        }
        return new JwtAuthenticationToken(jwt, List.copyOf(authorities), jwt.getSubject());
    }

    private static List<String> requireServiceScopes(Jwt jwt) {
        Object scopeClaim = jwt.getClaims().get(QuizopiaTokenClaims.SCOPE);
        if (!(scopeClaim instanceof Collection<?> rawScopes) || rawScopes.isEmpty()) {
            throw invalidToken();
        }

        List<String> scopes = new ArrayList<>(rawScopes.size());
        for (Object rawScope : rawScopes) {
            if (!(rawScope instanceof String scope) || scope.isBlank()) {
                throw invalidToken();
            }
            scopes.add(scope);
        }
        return List.copyOf(scopes);
    }

    private static void requireUuidSubject(Jwt jwt) {
        requireSubject(jwt);
        try {
            if (!UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())) {
                throw invalidToken();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private static void requireSubject(Jwt jwt) {
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw invalidToken();
        }
    }

    private static InvalidBearerTokenException invalidToken() {
        return new InvalidBearerTokenException("Invalid Quizopia principal claims");
    }
}
