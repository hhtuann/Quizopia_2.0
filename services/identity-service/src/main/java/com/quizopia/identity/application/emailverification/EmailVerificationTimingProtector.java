package com.quizopia.identity.application.emailverification;

import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public final class EmailVerificationTimingProtector {
    private static final String DUMMY_OTP = "000000";

    private final PasswordEncoder passwordEncoder;
    private final String dummyOtpHash;

    public EmailVerificationTimingProtector(
            @Qualifier("serviceClientPasswordEncoder") PasswordEncoder passwordEncoder) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.dummyOtpHash = Objects.requireNonNull(
                passwordEncoder.encode(DUMMY_OTP), "PasswordEncoder returned null for dummy OTP");
    }

    public void balanceConfirmation(RawEmailVerificationOtp presentedOtp) {
        Objects.requireNonNull(presentedOtp, "presentedOtp");
        passwordEncoder.matches(presentedOtp.value(), dummyOtpHash);
    }
}
