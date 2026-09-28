package com.quizopia.identity.application.refresh;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.persistence.entity.RefreshTokenEntity;
import com.quizopia.identity.persistence.entity.RefreshTokenFamilyEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.repository.RefreshTokenFamilyRepository;
import com.quizopia.identity.persistence.repository.RefreshTokenRepository;
import com.quizopia.identity.persistence.repository.UserAccessRevocationRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.refresh.RefreshCredentialGenerator;
import com.quizopia.identity.security.refresh.RefreshCredentialHasher;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RefreshSessionTransaction {
    private final UserAccountRepository userAccountRepository;
    private final RefreshTokenFamilyRepository refreshTokenFamilyRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserAccessRevocationRepository revocationRepository;
    private final RefreshCredentialGenerator credentialGenerator;
    private final RefreshCredentialHasher credentialHasher;
    private final EntityManager entityManager;

    public RefreshSessionTransaction(
            UserAccountRepository userAccountRepository,
            RefreshTokenFamilyRepository refreshTokenFamilyRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            UserAccessRevocationRepository revocationRepository,
            RefreshCredentialGenerator credentialGenerator,
            RefreshCredentialHasher credentialHasher,
            EntityManager entityManager) {
        this.userAccountRepository = userAccountRepository;
        this.refreshTokenFamilyRepository = refreshTokenFamilyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.revocationRepository = revocationRepository;
        this.credentialGenerator = credentialGenerator;
        this.credentialHasher = credentialHasher;
        this.entityManager = entityManager;
    }

    @Transactional
    public RefreshCredentialIssuance issueInitial(UUID userId, Instant familyExpiresAt) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");

        UserAccountEntity user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown identity user"));
        RefreshTokenFamilyEntity family =
                refreshTokenFamilyRepository.saveAndFlush(new RefreshTokenFamilyEntity(user, familyExpiresAt));
        RawRefreshCredential credential = credentialGenerator.generate();
        RefreshTokenEntity token =
                refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, credentialHasher.hash(credential)));
        return new RefreshCredentialIssuance(family.getId(), token.getId(), credential, family.getExpiresAt());
    }

    @Transactional
    public Optional<RefreshCredentialIssuance> issueInitialIfEligible(
            UUID userId, Instant authenticatedAt, Instant familyExpiresAt) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");

        // Authentication may have loaded this account earlier in the surrounding login
        // transaction. Clear that snapshot before the lock so eligibility is evaluated from the
        // authoritative row version that serialized with disable/account-state changes.
        entityManager.clear();
        UserAccountEntity user = userAccountRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null
                || !AccountLifecycleStatus.ACTIVE.equals(user.getAccountStatus())
                || user.getEmailVerifiedAt() == null
                || userRoleRepository.findAllByUser_Id(userId).stream()
                        .noneMatch(role -> role.getRole() == UserRole.STUDENT)
                || revocationRepository
                        .findById(userId)
                        .filter(revocation -> !authenticatedAt.isAfter(revocation.getRevokedBefore()))
                        .isPresent()) {
            return Optional.empty();
        }

        RefreshTokenFamilyEntity family =
                refreshTokenFamilyRepository.saveAndFlush(new RefreshTokenFamilyEntity(user, familyExpiresAt));
        RawRefreshCredential credential = credentialGenerator.generate();
        RefreshTokenEntity token =
                refreshTokenRepository.saveAndFlush(new RefreshTokenEntity(family, credentialHasher.hash(credential)));
        return Optional.of(
                new RefreshCredentialIssuance(family.getId(), token.getId(), credential, family.getExpiresAt()));
    }

    @Transactional
    public RefreshRotationResult rotate(RawRefreshCredential presentedCredential, Instant now) {
        Objects.requireNonNull(presentedCredential, "presentedCredential");
        Objects.requireNonNull(now, "now");

        byte[] presentedHash = credentialHasher.hash(presentedCredential);
        RefreshTokenEntity presentedToken =
                refreshTokenRepository.findByTokenHash(presentedHash).orElse(null);
        if (presentedToken == null) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.UNKNOWN_CREDENTIAL);
        }

        UUID familyId = presentedToken.getFamily().getId();
        entityManager.clear();
        RefreshTokenFamilyEntity family = refreshTokenFamilyRepository
                .findByIdForUpdate(familyId)
                .orElseThrow(() -> new IllegalStateException("Refresh token family disappeared"));
        presentedToken = refreshTokenRepository.findById(presentedToken.getId()).orElseThrow();
        if (family.getRevokedAt() != null) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.REVOKED_FAMILY);
        }
        if (presentedToken.getConsumedAt() != null) {
            family.setRevokedAt(now);
            return RefreshRotationResult.rejected(RefreshRotationStatus.REUSE_DETECTED);
        }
        long cookieMaxAgeSeconds = Duration.between(now, family.getExpiresAt()).getSeconds();
        if (cookieMaxAgeSeconds <= 0) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.EXPIRED_FAMILY);
        }
        UUID userId = family.getUser().getId();
        UserAccountEntity user = userAccountRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null
                || !AccountLifecycleStatus.ACTIVE.equals(user.getAccountStatus())
                || user.getEmailVerifiedAt() == null
                || userRoleRepository.findAllByUser_Id(userId).stream()
                        .noneMatch(role -> role.getRole() == UserRole.STUDENT)) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.INELIGIBLE_ACCOUNT);
        }
        if (revocationRepository
                .findById(userId)
                .filter(revocation -> !family.getCreatedAt().isAfter(revocation.getRevokedBefore()))
                .isPresent()) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.REVOKED_ACCOUNT);
        }

        RawRefreshCredential replacementCredential = credentialGenerator.generate();
        RefreshTokenEntity replacementToken = refreshTokenRepository.saveAndFlush(
                new RefreshTokenEntity(family, credentialHasher.hash(replacementCredential)));

        // Every normal rotation first holds this family's PESSIMISTIC_WRITE lock, so a
        // second legitimate refresh worker cannot make this CAS return zero. Keep the
        // check as a fail-closed guard for out-of-band writes, lock/repository
        // regressions, or corrupted persistence state.
        if (refreshTokenRepository.consumeIfUnused(presentedToken.getId(), now) != 1) {
            throw new RefreshRotationRaceLostException();
        }

        presentedToken.setConsumedAt(now);
        presentedToken.setReplacedByTokenId(replacementToken.getId());
        refreshTokenRepository.saveAndFlush(presentedToken);
        return RefreshRotationResult.success(userId, replacementCredential, family.getExpiresAt(), cookieMaxAgeSeconds);
    }

    @Transactional
    public void revokeAfterRace(RawRefreshCredential presentedCredential, Instant now) {
        RefreshTokenEntity token = refreshTokenRepository
                .findByTokenHash(credentialHasher.hash(presentedCredential))
                .orElseThrow(() -> new IllegalStateException("Refresh token disappeared after a rotation race"));
        UUID familyId = token.getFamily().getId();
        entityManager.clear();
        RefreshTokenFamilyEntity family = refreshTokenFamilyRepository
                .findByIdForUpdate(familyId)
                .orElseThrow(() -> new IllegalStateException("Refresh token family disappeared"));
        if (family.getRevokedAt() == null) {
            family.setRevokedAt(now);
        }
    }
}
