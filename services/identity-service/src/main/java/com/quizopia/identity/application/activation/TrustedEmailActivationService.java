package com.quizopia.identity.application.activation;

import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class TrustedEmailActivationService {
    private final TrustedEmailActivationTransaction transaction;

    public TrustedEmailActivationService(TrustedEmailActivationTransaction transaction) {
        this.transaction = transaction;
    }

    public TrustedEmailActivationResult activate(TrustedEmailActivationInput input) {
        Objects.requireNonNull(input, "input");
        try {
            return transaction.activate(input);
        } catch (DataIntegrityViolationException exception) {
            if (VerifiedEmailOwnershipConstraint.isViolation(exception)) {
                return TrustedEmailActivationResult.conflict(input.userId());
            }
            throw exception;
        }
    }
}
