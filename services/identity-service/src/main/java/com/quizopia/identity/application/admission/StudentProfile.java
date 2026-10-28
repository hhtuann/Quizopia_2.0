package com.quizopia.identity.application.admission;

import java.util.UUID;

/** One resolved student profile row. toString is redacted: names never reach logs. */
public record StudentProfile(UUID studentId, String displayName) {
    public String toString() {
        return "StudentProfile[redacted]";
    }
}
