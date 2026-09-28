package com.quizopia.identity.application.activation;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationSendFence;
import com.quizopia.identity.persistence.emailverification.EmailVerificationIssuanceThrottlePersistence;
import com.quizopia.identity.persistence.emailverification.EmailVerificationOutboxPersistence;
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
    private final EmailVerificationIssuanceThrottlePersistence issuanceThrottle;
    private final EmailVerificationOutboxPersistence outboxPersistence;
    private final EmailVerificationSendFence sendFence;

    public TrustedEmailActivationTransaction(
            UserAccountRepository userAccountRepository,
            UserRoleRepository userRoleRepository,
            EmailVerificationIssuanceThrottlePersistence issuanceThrottle,
            EmailVerificationOutboxPersistence outboxPersistence,
            EmailVerificationSendFence sendFence) {
        this.userAccountRepository = userAccountRepository;
        this.userRoleRepository = userRoleRepository;
        this.issuanceThrottle = issuanceThrottle;
        this.outboxPersistence = outboxPersistence;
        this.sendFence = sendFence;
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

        // Account operations lock user, then the exact-email send fence, then the exact-email
        // issuance guard. Issuance uses the same order.
        sendFence.serializeMutation(user.getEmail());
        issuanceThrottle.lockExactEmail(user.getEmail());

        user.setEmailVerifiedAt(input.verifiedAt());
        user.setAccountStatus(AccountLifecycleStatus.ACTIVE);
        userAccountRepository.saveAndFlush(user);
        outboxPersistence.terminalizeActiveForOtherUsersByEmail(
                user.getEmail(), user.getId(), "EMAIL_OWNERSHIP_LOST", input.verifiedAt());

        boolean hasStudent = userRoleRepository.findAllByUser_Id(user.getId()).stream()
                .anyMatch(role -> role.getRole() == UserRole.STUDENT);
        if (!hasStudent) {
            userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        }
        return TrustedEmailActivationResult.activated(user.getId(), input.verifiedAt());
    }
}
