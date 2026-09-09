package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RawEmailVerificationOtpTest {
    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> RawEmailVerificationOtp.from(null));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u2003"})
    void rejectsTechnicallyBlankMaterial(String value) {
        assertThrows(IllegalArgumentException.class, () -> RawEmailVerificationOtp.from(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "fixture words", "A-b_!", "  preserved  ", "\u03a9\u4e2d"})
    void preservesArbitraryNonblankMaterialAndRedactsDiagnostics(String value) {
        RawEmailVerificationOtp otp = RawEmailVerificationOtp.from(value);
        assertEquals(value, otp.value());
        assertEquals("[REDACTED_EMAIL_VERIFICATION_OTP]", otp.toString());
        assertFalse(RawEmailVerificationOtp.class.isRecord());
    }

    @Test
    void wrapperDoesNotSelectAnOtpLength() {
        String fixture = "fixture".repeat(1000);
        assertEquals(fixture, RawEmailVerificationOtp.from(fixture).value());
    }
}
