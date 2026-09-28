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
    @ValueSource(strings = {" ", "12345", "1234567", "12a456", "１２３４５６", "+12345", " 123456"})
    void rejectsAnythingOtherThanSixAsciiDecimalDigits(String value) {
        assertThrows(IllegalArgumentException.class, () -> RawEmailVerificationOtp.from(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"000000", "012345", "999999"})
    void preservesSixDigitsIncludingLeadingZeroesAndRedactsDiagnostics(String value) {
        RawEmailVerificationOtp otp = RawEmailVerificationOtp.from(value);
        assertEquals(value, otp.value());
        assertEquals("[REDACTED_EMAIL_VERIFICATION_OTP]", otp.toString());
        assertFalse(RawEmailVerificationOtp.class.isRecord());
    }
}
