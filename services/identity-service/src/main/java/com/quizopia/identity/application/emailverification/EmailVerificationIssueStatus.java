package com.quizopia.identity.application.emailverification;

public enum EmailVerificationIssueStatus {
    ISSUED,
    COOLDOWN,
    ALREADY_VERIFIED,
    NOT_FOUND,
    CONFLICT
}
