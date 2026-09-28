package com.quizopia.identity.application.emailverification;

public enum EmailVerificationIssueStatus {
    ISSUED,
    COOLDOWN,
    HOURLY_LIMIT,
    ALREADY_VERIFIED,
    NOT_FOUND,
    CONFLICT
}
