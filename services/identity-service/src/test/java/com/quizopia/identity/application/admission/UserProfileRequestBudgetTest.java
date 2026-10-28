package com.quizopia.identity.application.admission;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.identity.configuration.UserProfileProperties;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UserProfileRequestBudgetTest {
    private final AtomicLong now = new AtomicLong();
    private final UserProfileRequestBudget budget =
            new UserProfileRequestBudget(new UserProfileProperties(true, true, "assessment-service", 8, 2), now::get);

    @Test
    void sequentialRequestsStayLimitedUntilEachAdmissionLeavesRollingMinute() {
        budget.acquire("assessment-service");
        now.set(Duration.ofSeconds(30).toNanos());
        budget.acquire("assessment-service");
        limited();
        now.set(Duration.ofSeconds(60).toNanos() - 1);
        limited();
        now.incrementAndGet();
        budget.acquire("assessment-service");
        limited();
        now.set(Duration.ofSeconds(90).toNanos());
        budget.acquire("assessment-service");
        limited();
    }

    @Test
    void foreignCallersCannotAllocateStateOrConsumeAssessmentBudget() {
        for (int i = 0; i < 100; i++) {
            var failure = assertThrows(AdmissionFailure.class, () -> budget.acquire("foreign-service"));
            assertEquals(403, failure.status());
        }
        budget.acquire("assessment-service");
        budget.acquire("assessment-service");
        limited();
    }

    @Test
    void concurrentRequestsCannotOvershootBudget() throws Exception {
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (var threads = Executors.newFixedThreadPool(16)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 100; i++)
                futures.add(threads.submit(() -> {
                    start.await();
                    try {
                        budget.acquire("assessment-service");
                        accepted.incrementAndGet();
                    } catch (AdmissionFailure failure) {
                        assertEquals(429, failure.status());
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            start.countDown();
            for (var future : futures) future.get();
        }
        assertEquals(2, accepted.get());
        assertEquals(98, rejected.get());
    }

    @Test
    void elapsedTimeWorksAcrossMonotonicClockWraparound() {
        now.set(Long.MAX_VALUE - Duration.ofSeconds(30).toNanos());
        budget.acquire("assessment-service");
        budget.acquire("assessment-service");
        now.addAndGet(Duration.ofMinutes(1).toNanos());
        budget.acquire("assessment-service");
    }

    private void limited() {
        var failure = assertThrows(AdmissionFailure.class, () -> budget.acquire("assessment-service"));
        assertEquals(429, failure.status());
        assertEquals("PROFILE_RATE_LIMITED", failure.code());
    }
}
