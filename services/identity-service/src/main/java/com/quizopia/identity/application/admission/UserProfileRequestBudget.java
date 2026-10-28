package com.quizopia.identity.application.admission;

import com.quizopia.identity.configuration.UserProfileProperties;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One rolling-minute budget for the sole admitted Assessment caller, per Identity instance.
 * Fixed memory; no user IDs, credentials, request bodies or caller-controlled keys are retained.
 * Token rotation and changing requested IDs cannot reset the budget.
 */
@Component
public final class UserProfileRequestBudget {
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();
    private final String assessmentClientId;
    private final long[] admitted;
    private final LongSupplier ticker;
    private int head;
    private int size;

    @Autowired
    public UserProfileRequestBudget(UserProfileProperties properties) {
        this(properties, System::nanoTime);
    }

    UserProfileRequestBudget(UserProfileProperties properties, LongSupplier ticker) {
        this.assessmentClientId = properties.assessmentClientId();
        this.admitted = new long[properties.requestsPerMinute()];
        this.ticker = ticker;
    }

    // Called only after the security chain verifies SERVICE, exact subject and dedicated scope.
    public synchronized void acquire(String authorizedCaller) {
        if (!assessmentClientId.equals(authorizedCaller)) throw new AdmissionFailure(403, "SERVICE_CALLER_FORBIDDEN");
        long now = ticker.getAsLong();
        while (size > 0 && now - admitted[head] >= WINDOW) {
            head = (head + 1) % admitted.length;
            size--;
        }
        if (size == admitted.length) throw new AdmissionFailure(429, "PROFILE_RATE_LIMITED");
        admitted[(head + size) % admitted.length] = now;
        size++;
    }
}
