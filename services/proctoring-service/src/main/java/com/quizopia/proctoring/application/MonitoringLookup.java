package com.quizopia.proctoring.application;

import java.util.UUID;

/** Local existing-session index only; callers must establish current resource authority first. */
public interface MonitoringLookup {
    UUID existingSession(UUID attemptId);
}
