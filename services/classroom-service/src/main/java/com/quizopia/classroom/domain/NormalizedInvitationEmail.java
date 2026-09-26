package com.quizopia.classroom.domain;

import java.util.Locale;

public record NormalizedInvitationEmail(String value) {
    public NormalizedInvitationEmail {
        value = RequiredText.require(value, "email").strip();
        int at = value.indexOf('@');
        if (at <= 0
                || at != value.lastIndexOf('@')
                || at == value.length() - 1
                || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("email must contain a local part and domain without whitespace");
        }
        value = value.substring(0, at + 1) + value.substring(at + 1).toLowerCase(Locale.ROOT);
        if (value.length() > 320) throw new IllegalArgumentException("email exceeds storage length");
    }

    @Override
    public String toString() {
        return "NormalizedInvitationEmail[redacted]";
    }
}
