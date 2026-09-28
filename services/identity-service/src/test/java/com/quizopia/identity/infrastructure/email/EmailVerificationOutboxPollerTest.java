package com.quizopia.identity.infrastructure.email;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxDispatcher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EmailVerificationOutboxPollerTest {
    @Test
    void overlappingLocalPollIsSkipped() throws Exception {
        EmailVerificationOutboxDispatcher dispatcher = mock(EmailVerificationOutboxDispatcher.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dispatcher.dispatchDueBatch()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release test poll");
            }
            return 0;
        });
        var poller = new EmailVerificationOutboxPoller(dispatcher);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(poller::poll);
            assertTrue(entered.await(30, TimeUnit.SECONDS));

            poller.poll();
            release.countDown();
            first.get(30, TimeUnit.SECONDS);

            verify(dispatcher, times(1)).dispatchDueBatch();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
