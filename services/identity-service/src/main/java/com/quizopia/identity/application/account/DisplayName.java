package com.quizopia.identity.application.account;

import java.text.Normalizer;

/**
 * Validation for the optional, user-provided display name. The value is never a verified legal
 * name. Input is NFC-normalized and Unicode-trimmed; the normalized value must be 1..100 code
 * points and must not contain ISO control characters. A clear (null) is not handled here: callers
 * decide whether null clears the stored value.
 */
public final class DisplayName {
    public static final int MAX_LENGTH = 100;

    private DisplayName() {}

    public static String normalize(String value) {
        if (value == null) throw new DisplayNameInvalidException();
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.isEmpty()) throw new DisplayNameInvalidException();
        if (normalized.codePointCount(0, normalized.length()) > MAX_LENGTH) throw new DisplayNameInvalidException();
        if (normalized
                .codePoints()
                .anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.SURROGATE))
            throw new DisplayNameInvalidException();
        return normalized;
    }
}
