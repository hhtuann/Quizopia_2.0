package com.quizopia.identity.application.login;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class InitialLoginSessionPolicy {
    public static final Duration FAMILY_LIFETIME = Duration.ofDays(7);

    public Instant familyExpiresAt(Instant loginTime) {
        return Objects.requireNonNull(loginTime, "loginTime").plus(FAMILY_LIFETIME);
    }
}
