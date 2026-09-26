package com.quizopia.identity.application.activation;

import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class TrustedEmailActivationService {
    private final TrustedEmailActivationTransaction transaction;

    public TrustedEmailActivationService(TrustedEmailActivationTransaction transaction) {
        this.transaction = transaction;
    }

    public TrustedEmailActivationResult activate(TrustedEmailActivationInput input) {
        return transaction.activate(Objects.requireNonNull(input, "input"));
    }
}
