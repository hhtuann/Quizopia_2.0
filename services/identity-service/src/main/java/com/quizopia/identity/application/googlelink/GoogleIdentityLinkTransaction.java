package com.quizopia.identity.application.googlelink;

import com.quizopia.identity.persistence.googlelink.GoogleIdentityLinkPersistence;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class GoogleIdentityLinkTransaction {
    private final GoogleIdentityLinkPersistence persistence;

    public GoogleIdentityLinkTransaction(GoogleIdentityLinkPersistence persistence) {
        this.persistence = persistence;
    }

    @Transactional
    public GoogleIdentityLinkResult resolve(VerifiedGoogleIdentity identity) {
        Objects.requireNonNull(identity, "identity");

        List<GoogleLinkCandidate> candidates = persistence.findCandidatesForUpdate(identity.verifiedEmail());
        Optional<UUID> existingOwner = persistence.findGoogleSubjectOwner(identity.providerSubject());
        if (existingOwner.isPresent()) {
            if (candidates.isEmpty()) {
                return GoogleIdentityLinkResult.alreadyLinked(existingOwner.orElseThrow());
            }
            if (candidates.size() == 1 && candidates.getFirst().userId().equals(existingOwner.orElseThrow())) {
                return GoogleIdentityLinkResult.alreadyLinked(existingOwner.orElseThrow());
            }
            return GoogleIdentityLinkResult.conflict();
        }

        if (candidates.isEmpty()) {
            return GoogleIdentityLinkResult.noSafeMatch();
        }
        if (candidates.size() != 1) {
            return GoogleIdentityLinkResult.conflict();
        }

        GoogleLinkCandidate candidate = candidates.getFirst();
        if (!candidate.emailVerified()) {
            return GoogleIdentityLinkResult.noSafeMatch();
        }
        if (persistence.hasDifferentGoogleSubject(candidate.userId(), identity.providerSubject())) {
            return GoogleIdentityLinkResult.conflict();
        }

        if (persistence.insertGoogleIdentity(candidate.userId(), identity.providerSubject()) == 1) {
            return GoogleIdentityLinkResult.linkedExistingUser(candidate.userId());
        }

        Optional<UUID> racedOwner = persistence.findGoogleSubjectOwner(identity.providerSubject());
        if (racedOwner.isPresent() && racedOwner.orElseThrow().equals(candidate.userId())) {
            return GoogleIdentityLinkResult.alreadyLinked(candidate.userId());
        }
        return GoogleIdentityLinkResult.conflict();
    }
}
