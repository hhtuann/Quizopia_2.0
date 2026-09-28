package com.quizopia.identity.application.logout;

import com.quizopia.identity.persistence.entity.RefreshTokenEntity;
import com.quizopia.identity.persistence.entity.RefreshTokenFamilyEntity;
import com.quizopia.identity.persistence.repository.RefreshTokenFamilyRepository;
import com.quizopia.identity.persistence.repository.RefreshTokenRepository;
import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.refresh.RefreshCredentialHasher;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class CurrentSessionLogoutTransaction {
    private final RefreshTokenFamilyRepository familyRepository;
    private final RefreshTokenRepository tokenRepository;
    private final RefreshCredentialHasher credentialHasher;
    private final EntityManager entityManager;

    public CurrentSessionLogoutTransaction(
            RefreshTokenFamilyRepository familyRepository,
            RefreshTokenRepository tokenRepository,
            RefreshCredentialHasher credentialHasher,
            EntityManager entityManager) {
        this.familyRepository = familyRepository;
        this.tokenRepository = tokenRepository;
        this.credentialHasher = credentialHasher;
        this.entityManager = entityManager;
    }

    @Transactional
    public void revokePresentedFamily(RawRefreshCredential credential, Instant now) {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(now, "now");

        RefreshTokenEntity presentedToken = tokenRepository
                .findByTokenHash(credentialHasher.hash(credential))
                .orElse(null);
        if (presentedToken == null) {
            return;
        }

        UUID familyId = presentedToken.getFamily().getId();
        UUID tokenId = presentedToken.getId();
        entityManager.clear();
        RefreshTokenFamilyEntity family = familyRepository
                .findByIdForUpdate(familyId)
                .orElseThrow(() -> new IllegalStateException("Refresh token family disappeared"));
        presentedToken = tokenRepository
                .findById(tokenId)
                .orElseThrow(() -> new IllegalStateException("Refresh token disappeared"));

        if (family.getRevokedAt() != null) {
            return;
        }
        if (presentedToken.getConsumedAt() != null) {
            family.setRevokedAt(now);
            return;
        }
        if (!family.getExpiresAt().isAfter(now)) {
            return;
        }
        family.setRevokedAt(now);
    }
}
