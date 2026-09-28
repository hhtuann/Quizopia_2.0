package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.emailverification.SecureEmailVerificationOtpGenerator;
import org.junit.jupiter.api.Test;

class SecureEmailVerificationOtpGeneratorTest {
    @Test
    void productionGeneratorAlwaysCreatesSixAsciiDecimalDigitsWithoutDiagnosticDisclosure() {
        SecureEmailVerificationOtpGenerator generator = new SecureEmailVerificationOtpGenerator();

        for (int sample = 0; sample < 1_000; sample++) {
            RawEmailVerificationOtp otp = generator.generate();
            assertTrue(otp.value().matches("[0-9]{6}"));
            assertFalse(otp.toString().contains(otp.value()));
        }
    }
}
