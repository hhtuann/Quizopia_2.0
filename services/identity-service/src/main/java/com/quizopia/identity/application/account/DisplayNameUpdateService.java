package com.quizopia.identity.application.account;

import com.quizopia.identity.persistence.repository.UserAccountRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service display-name update. Only the authenticated subject's own account is ever touched;
 * the caller resolves the account id, never the request. A null candidate clears the stored name.
 */
@Service
@Profile("!test")
public class DisplayNameUpdateService {
    private final UserAccountRepository userAccountRepository;

    public DisplayNameUpdateService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public void update(UUID userId, String rawDisplayName) {
        Objects.requireNonNull(userId, "userId");
        String normalized = rawDisplayName == null ? null : DisplayName.normalize(rawDisplayName);
        var user = userAccountRepository
                .findById(userId)
                .orElseThrow(() -> new IllegalStateException("Display-name target account is missing"));
        user.setDisplayName(normalized);
        userAccountRepository.saveAndFlush(user);
    }
}
