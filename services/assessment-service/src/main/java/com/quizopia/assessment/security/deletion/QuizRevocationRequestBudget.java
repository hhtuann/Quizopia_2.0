package com.quizopia.assessment.security.deletion;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.configuration.AssessmentDeletionProperties;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * One rolling-minute budget for the sole admitted Quiz deletion caller, per Assessment instance.
 * Fixed memory; no operation ids, quiz ids, credentials or request bodies are retained. Token
 * rotation and changing requested ids cannot reset the budget.
 */
@Component
public final class QuizRevocationRequestBudget {
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();
    private final String quizServiceClientId;
    private final long[] admitted;
    private final LongSupplier ticker;
    private int head;
    private int size;

    @org.springframework.beans.factory.annotation.Autowired
    public QuizRevocationRequestBudget(AssessmentDeletionProperties properties) {
        this(properties, System::nanoTime);
    }

    public QuizRevocationRequestBudget(AssessmentDeletionProperties properties, LongSupplier ticker) {
        this.quizServiceClientId = properties.quizServiceClientId();
        this.admitted = new long[properties.requestsPerMinute()];
        this.ticker = ticker;
    }

    // Called only after the security chain verifies SERVICE, exact subject and dedicated scope.
    public synchronized void acquire(String authorizedCaller) {
        if (!quizServiceClientId.equals(authorizedCaller))
            throw new PublicationFailure(403, "SERVICE_CALLER_FORBIDDEN");
        long now = ticker.getAsLong();
        while (size > 0 && now - admitted[head] >= WINDOW) {
            head = (head + 1) % admitted.length;
            size--;
        }
        if (size == admitted.length) throw new PublicationFailure(429, "REVOCATION_RATE_LIMITED");
        admitted[(head + size) % admitted.length] = now;
        size++;
    }
}
