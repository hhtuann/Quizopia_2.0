package com.quizopia.identity.application.registration;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.security.password.RawLocalPassword;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class LocalRegistrationTransaction {
    private final UserAccountRepository userAccountRepository;
    private final LocalCredentialRepository localCredentialRepository;
    private final PasswordEncoder passwordEncoder;

    public LocalRegistrationTransaction(
            UserAccountRepository userAccountRepository,
            LocalCredentialRepository localCredentialRepository,
            @Qualifier("serviceClientPasswordEncoder") PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.localCredentialRepository = localCredentialRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public LocalRegistrationResult register(LocalRegistrationInput input) {
        Objects.requireNonNull(input, "input");
        RawLocalPassword rawPassword = input.rawPassword();
        String encodedPassword =
                Objects.requireNonNull(passwordEncoder.encode(rawPassword.value()), "PasswordEncoder returned null");
        if (encodedPassword.isBlank()) {
            throw new IllegalStateException("PasswordEncoder returned a blank encoded password");
        }

        UserAccountEntity user = new UserAccountEntity(input.email(), input.username());
        user.setAccountStatus(AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION);
        UserAccountEntity persistedUser = userAccountRepository.saveAndFlush(user);
        localCredentialRepository.saveAndFlush(new LocalCredentialEntity(persistedUser, encodedPassword));
        return LocalRegistrationResult.created(persistedUser.getId());
    }
}
