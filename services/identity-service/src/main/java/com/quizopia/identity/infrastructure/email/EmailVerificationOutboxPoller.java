package com.quizopia.identity.infrastructure.email;

import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxDispatcher;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class EmailVerificationOutboxPoller {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationOutboxPoller.class);

    private final EmailVerificationOutboxDispatcher dispatcher;
    private final AtomicBoolean polling = new AtomicBoolean();

    public EmailVerificationOutboxPoller(EmailVerificationOutboxDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Scheduled(
            fixedDelayString = "${quizopia.identity.email-outbox.dispatcher.polling-interval:PT5S}",
            initialDelayString = "${quizopia.identity.email-outbox.dispatcher.polling-interval:PT5S}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            LOGGER.warn("Skipped overlapping local verification email outbox poll");
            return;
        }
        try {
            dispatcher.dispatchDueBatch();
        } catch (RuntimeException exception) {
            LOGGER.error("Verification email outbox poll failed with sanitized internal error");
        } finally {
            polling.set(false);
        }
    }
}
