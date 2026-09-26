package com.quizopia.identity.persistence.googlelink;

import com.quizopia.identity.application.googlelink.GoogleLinkCandidate;
import com.quizopia.identity.persistence.entity.ExternalProviderIdentityEntity;
import com.quizopia.identity.persistence.repository.ExternalProviderIdentityRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class GoogleIdentityLinkPersistence {
    private static final String GOOGLE_PROVIDER = "google";

    private final UserAccountRepository userAccountRepository;
    private final ExternalProviderIdentityRepository externalProviderIdentityRepository;

    public GoogleIdentityLinkPersistence(
            UserAccountRepository userAccountRepository,
            ExternalProviderIdentityRepository externalProviderIdentityRepository) {
        this.userAccountRepository = userAccountRepository;
        this.externalProviderIdentityRepository = externalProviderIdentityRepository;
    }

    public List<GoogleLinkCandidate> findCandidatesForUpdate(String verifiedEmail) {
        return userAccountRepository.findAllByEmailForLinking(verifiedEmail).stream()
                .map(account -> new GoogleLinkCandidate(account.getId(), account.getEmailVerifiedAt() != null))
                .toList();
    }

    public Optional<UUID> findGoogleSubjectOwner(String providerSubject) {
        return externalProviderIdentityRepository
                .findByProviderAndProviderSubject(GOOGLE_PROVIDER, providerSubject)
                .map(identity -> identity.getUser().getId());
    }

    public boolean hasDifferentGoogleSubject(UUID userId, String providerSubject) {
        return externalProviderIdentityRepository.findAllByUserIdAndProvider(userId, GOOGLE_PROVIDER).stream()
                .map(ExternalProviderIdentityEntity::getProviderSubject)
                .anyMatch(existingSubject -> !existingSubject.equals(providerSubject));
    }

    public int insertGoogleIdentity(UUID userId, String providerSubject) {
        Instant now = Instant.now();
        return externalProviderIdentityRepository.insertIfAbsent(
                UUID.randomUUID(), userId, GOOGLE_PROVIDER, providerSubject, now, now);
    }
}
