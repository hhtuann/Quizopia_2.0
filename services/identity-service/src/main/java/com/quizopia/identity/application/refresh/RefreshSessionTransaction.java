package com.quizopia.identity.application.refresh;

import com.quizopia.identity.persistence.entity.RefreshTokenEntity;
import com.quizopia.identity.persistence.entity.RefreshTokenFamilyEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.repository.RefreshTokenFamilyRepository;
import com.quizopia.identity.persistence.repository.RefreshTokenRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.refresh.RefreshCredentialGenerator;
import com.quizopia.identity.security.refresh.RefreshCredentialHasher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Objects;
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
    private final RefreshCredentialGenerator credentialGenerator;
    private final RefreshCredentialHasher credentialHasher;
    private final EntityManager entityManager;

    public RefreshSessionTransaction(
            UserAccountRepository userAccountRepository,
            RefreshTokenFamilyRepository refreshTokenFamilyRepository,
            RefreshTokenRepository refreshTokenRepository,
            RefreshCredentialGenerator credentialGenerator,
            RefreshCredentialHasher credentialHasher,
            EntityManager entityManager) {
        this.userAccountRepository = userAccountRepository;
        this.refreshTokenFamilyRepository = refreshTokenFamilyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
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
        return new RefreshCredentialIssuance(family.getId(), token.getId(), credential);
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
        entityManager.refresh(family, LockModeType.PESSIMISTIC_READ);
        if (presentedToken.getConsumedAt() != null) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.REUSE_DETECTED);
        }
        if (family.getRevokedAt() != null) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.REVOKED_FAMILY);
        }
        if (!family.getExpiresAt().isAfter(now)) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.EXPIRED_FAMILY);
        }

        RawRefreshCredential replacementCredential = credentialGenerator.generate();
        RefreshTokenEntity replacementToken = refreshTokenRepository.saveAndFlush(
                new RefreshTokenEntity(family, credentialHasher.hash(replacementCredential)));

        if (refreshTokenRepository.consumeIfUnused(presentedToken.getId(), now) != 1) {
            throw new RefreshRotationRaceLostException();
        }

        presentedToken.setConsumedAt(now);
        presentedToken.setReplacedByTokenId(replacementToken.getId());
        refreshTokenRepository.saveAndFlush(presentedToken);
        return RefreshRotationResult.success(replacementCredential);
    }
}
