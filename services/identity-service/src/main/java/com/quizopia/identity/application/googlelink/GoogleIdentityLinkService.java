package com.quizopia.identity.application.googlelink;

import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class GoogleIdentityLinkService {
    private final GoogleIdentityLinkTransaction transaction;

    public GoogleIdentityLinkService(GoogleIdentityLinkTransaction transaction) {
        this.transaction = transaction;
    }

    public GoogleIdentityLinkResult resolve(VerifiedGoogleIdentity identity) {
        return transaction.resolve(Objects.requireNonNull(identity, "identity"));
    }
}
