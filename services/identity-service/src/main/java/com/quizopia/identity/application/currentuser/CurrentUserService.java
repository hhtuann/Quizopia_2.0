package com.quizopia.identity.application.currentuser;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.revocation.AuthoritativeRevocationService;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class CurrentUserService {
    private final UserAccountRepository userAccountRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuthoritativeRevocationService revocationService;

    public CurrentUserService(
            UserAccountRepository userAccountRepository,
            UserRoleRepository userRoleRepository,
            AuthoritativeRevocationService revocationService) {
        this.userAccountRepository = userAccountRepository;
        this.userRoleRepository = userRoleRepository;
        this.revocationService = revocationService;
    }

    @Transactional(readOnly = true)
    public Optional<CurrentUserProfile> findEligible(UUID userId, Instant tokenIssuedAt) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tokenIssuedAt, "tokenIssuedAt");

        UserAccountEntity user = userAccountRepository.findById(userId).orElse(null);
        if (user == null
                || !AccountLifecycleStatus.ACTIVE.equals(user.getAccountStatus())
                || user.getEmailVerifiedAt() == null) {
            return Optional.empty();
        }

        List<String> roles = userRoleRepository.findAllByUser_Id(userId).stream()
                .map(role -> role.getRole().name())
                .distinct()
                .sorted()
                .toList();
        if (!roles.contains(UserRole.STUDENT.name())) {
            return Optional.empty();
        }

        if (revocationService
                .find(userId)
                .filter(revocation -> !tokenIssuedAt.isAfter(revocation.revokedBefore()))
                .isPresent()) {
            return Optional.empty();
        }

        return Optional.of(new CurrentUserProfile(user.getId(), user.getUsername(), user.getEmail(), roles));
    }
}
