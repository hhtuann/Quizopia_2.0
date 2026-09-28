package com.quizopia.identity.security.emailverification;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SecureEmailVerificationOtpGenerator implements EmailVerificationOtpGenerator {
    private static final int OTP_BOUND = 1_000_000;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public RawEmailVerificationOtp generate() {
        return RawEmailVerificationOtp.from(String.format(Locale.ROOT, "%06d", secureRandom.nextInt(OTP_BOUND)));
    }
}
