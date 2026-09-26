package com.quizopia.identity.application.googlelink;

import java.util.Objects;

public record VerifiedGoogleIdentity(String providerSubject, String verifiedEmail) {
    private static final int MAX_SUBJECT_LENGTH = 512;
    private static final int MAX_EMAIL_LENGTH = 320;

    public VerifiedGoogleIdentity {
        Objects.requireNonNull(providerSubject, "providerSubject");
        Objects.requireNonNull(verifiedEmail, "verifiedEmail");
        if (providerSubject.isBlank() || providerSubject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("providerSubject must be non-blank and at most 512 characters");
        }
        if (verifiedEmail.isBlank() || verifiedEmail.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("verifiedEmail must be non-blank and at most 320 characters");
        }
    }
}
