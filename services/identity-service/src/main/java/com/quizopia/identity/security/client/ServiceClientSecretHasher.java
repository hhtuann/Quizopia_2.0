package com.quizopia.identity.security.client;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class ServiceClientSecretHasher {
    private final PasswordEncoder passwordEncoder;

    public ServiceClientSecretHasher(@Qualifier("serviceClientPasswordEncoder") PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public String encode(RawServiceClientSecret secret) {
        return passwordEncoder.encode(Objects.requireNonNull(secret, "secret").value());
    }

    public boolean matches(RawServiceClientSecret secret, String encodedSecret) {
        return passwordEncoder.matches(
                Objects.requireNonNull(secret, "secret").value(),
                Objects.requireNonNull(encodedSecret, "encodedSecret"));
    }
}
