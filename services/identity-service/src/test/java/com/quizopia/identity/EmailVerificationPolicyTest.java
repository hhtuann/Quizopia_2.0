package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quizopia.identity.application.emailverification.EmailVerificationPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailVerificationPolicyTest {
    @Test
    void requiresBothDurations() {
        assertThrows(NullPointerException.class, () -> new EmailVerificationPolicy(null, 1, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new EmailVerificationPolicy(Duration.ofSeconds(1), 1, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void expiryMustBePositive(long seconds) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailVerificationPolicy(Duration.ofSeconds(seconds), 1, Duration.ZERO));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void attemptLimitMustBePositive(int attempts) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailVerificationPolicy(Duration.ofSeconds(1), attempts, Duration.ZERO));
    }

    @Test
    void cooldownMustNotBeNegative() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailVerificationPolicy(Duration.ofSeconds(1), 1, Duration.ofNanos(-1)));
    }

    @Test
    void onlyTechnicalBoundsApplyIncludingZeroCooldown() {
        EmailVerificationPolicy policy =
                new EmailVerificationPolicy(Duration.ofNanos(1), Integer.MAX_VALUE, Duration.ZERO);
        assertEquals(Duration.ofNanos(1), policy.expiry());
        assertEquals(Integer.MAX_VALUE, policy.maxAttempts());
        assertEquals(Duration.ZERO, policy.resendCooldown());
    }
}
