package com.quizopia.identity.application.localauthentication;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class LocalAuthenticationService {
    private static final String DUMMY_PASSWORD = "quizopia-local-authentication-dummy-password";

    private final UserAccountRepository userAccountRepository;
    private final LocalCredentialRepository localCredentialRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public LocalAuthenticationService(
            UserAccountRepository userAccountRepository,
            LocalCredentialRepository localCredentialRepository,
            UserRoleRepository userRoleRepository,
            @Qualifier("serviceClientPasswordEncoder") PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.localCredentialRepository = localCredentialRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = Objects.requireNonNull(
                passwordEncoder.encode(DUMMY_PASSWORD), "PasswordEncoder returned null for dummy credential");
    }

    @Transactional(readOnly = true)
    public LocalAuthenticationResult authenticate(LocalAuthenticationInput input) {
        Objects.requireNonNull(input, "input");
        Optional<UserAccountEntity> usernameMatch = userAccountRepository.findByUsername(input.identifier());
        Optional<UserAccountEntity> verifiedEmailMatch = userAccountRepository.findVerifiedByEmail(input.identifier());
        UserAccountEntity resolvedUser =
                resolveDistinctUser(usernameMatch, verifiedEmailMatch).orElse(null);

        String passwordHash = resolvedUser == null
                ? dummyPasswordHash
                : localCredentialRepository
                        .findByUserId(resolvedUser.getId())
                        .map(credential -> credential.getPasswordHash())
                        .orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(input.rawPassword().value(), passwordHash);
        if (resolvedUser == null || !passwordMatches || !isEligible(resolvedUser)) {
            return LocalAuthenticationResult.failed();
        }
        return LocalAuthenticationResult.authenticated(resolvedUser.getId());
    }

    private Optional<UserAccountEntity> resolveDistinctUser(
            Optional<UserAccountEntity> usernameMatch, Optional<UserAccountEntity> verifiedEmailMatch) {
        if (usernameMatch.isEmpty()) {
            return verifiedEmailMatch;
        }
        if (verifiedEmailMatch.isEmpty()) {
            return usernameMatch;
        }
        UUID usernameUserId = usernameMatch.orElseThrow().getId();
        return usernameUserId.equals(verifiedEmailMatch.orElseThrow().getId()) ? usernameMatch : Optional.empty();
    }

    private boolean isEligible(UserAccountEntity user) {
        return AccountLifecycleStatus.ACTIVE.equals(user.getAccountStatus())
                && user.getEmailVerifiedAt() != null
                && userRoleRepository.findAllByUser_Id(user.getId()).stream()
                        .anyMatch(role -> role.getRole() == UserRole.STUDENT);
    }
}
