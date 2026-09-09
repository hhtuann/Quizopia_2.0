package com.quizopia.identity.application.activation;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TrustedEmailActivationTransaction {
    private final UserAccountRepository userAccountRepository;
    private final UserRoleRepository userRoleRepository;

    public TrustedEmailActivationTransaction(
            UserAccountRepository userAccountRepository, UserRoleRepository userRoleRepository) {
        this.userAccountRepository = userAccountRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional
    public TrustedEmailActivationResult activate(TrustedEmailActivationInput input) {
        Objects.requireNonNull(input, "input");
        UserAccountEntity user =
                userAccountRepository.findByIdForUpdate(input.userId()).orElse(null);
        if (user == null) {
            return TrustedEmailActivationResult.notFound(input.userId());
        }

        if (AccountLifecycleStatus.ACTIVE.equals(user.getAccountStatus())) {
            if (user.getEmailVerifiedAt() == null) {
                return TrustedEmailActivationResult.conflict(user.getId());
            }
            return TrustedEmailActivationResult.alreadyActive(user.getId(), user.getEmailVerifiedAt());
        }
        if (!AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION.equals(user.getAccountStatus())
                || user.getEmailVerifiedAt() != null) {
            return TrustedEmailActivationResult.conflict(user.getId());
        }

        user.setEmailVerifiedAt(input.verifiedAt());
        user.setAccountStatus(AccountLifecycleStatus.ACTIVE);
        userAccountRepository.saveAndFlush(user);

        boolean hasStudent = userRoleRepository.findAllByUser_Id(user.getId()).stream()
                .anyMatch(role -> role.getRole() == UserRole.STUDENT);
        if (!hasStudent) {
            userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        }
        return TrustedEmailActivationResult.activated(user.getId(), input.verifiedAt());
    }
}
