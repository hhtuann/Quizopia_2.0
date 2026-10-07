package com.quizopia.identity.application.teacherenablement;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.persistence.entity.RoleGrantAuditAction;
import com.quizopia.identity.persistence.entity.RoleGrantAuditEntity;
import com.quizopia.identity.persistence.entity.RoleGrantAuditSource;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.RoleGrantAuditRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TeacherEnablementTransaction {
    private final UserAccountRepository userAccountRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleGrantAuditRepository auditRepository;
    private final Clock clock;

    public TeacherEnablementTransaction(
            UserAccountRepository userAccountRepository,
            UserRoleRepository userRoleRepository,
            RoleGrantAuditRepository auditRepository,
            @Qualifier("identityClock") Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.userRoleRepository = userRoleRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    @Transactional
    public TeacherEnablementStatus enable(UUID authenticatedUserId) {
        UserAccountEntity user =
                userAccountRepository.findByIdForUpdate(authenticatedUserId).orElse(null);
        if (user == null
                || !AccountLifecycleStatus.ACTIVE.equals(user.getAccountStatus())
                || user.getEmailVerifiedAt() == null) {
            return TeacherEnablementStatus.INELIGIBLE;
        }

        List<UserRoleEntity> currentRoles = userRoleRepository.findAllByUser_Id(authenticatedUserId);
        if (currentRoles.stream().noneMatch(role -> role.getRole() == UserRole.STUDENT)) {
            return TeacherEnablementStatus.INELIGIBLE;
        }
        if (currentRoles.stream().anyMatch(role -> role.getRole() == UserRole.TEACHER)) {
            return TeacherEnablementStatus.ALREADY_ENABLED;
        }

        userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.TEACHER));
        auditRepository.saveAndFlush(new RoleGrantAuditEntity(
                user,
                user,
                UserRole.TEACHER,
                RoleGrantAuditAction.ROLE_GRANTED,
                RoleGrantAuditSource.SELF_SERVICE,
                clock.instant()));
        return TeacherEnablementStatus.ENABLED;
    }
}
