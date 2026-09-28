package com.quizopia.identity.application.emailverification.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.quizopia.identity.configuration.EmailOutboxDispatcherProperties;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.outbox.EncryptedOutboxPayload;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.quizopia.identity.security.outbox.OutboxPayloadDecryptionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class EmailVerificationOutboxDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-09-23T03:00:00.123456Z");
    private static final RawEmailVerificationOtp OTP = RawEmailVerificationOtp.from("012345");

    private EmailVerificationOutboxStore store;
    private OutboxPayloadCipher cipher;
    private VerificationEmailDelivery delivery;
    private EmailVerificationSendFence sendFence;
    private EmailOutboxDispatcherProperties properties;
    private ScheduledExecutorService heartbeatExecutor;

    @BeforeEach
    void setUp() {
        store = mock(EmailVerificationOutboxStore.class);
        cipher = mock(OutboxPayloadCipher.class);
        delivery = mock(VerificationEmailDelivery.class);
        sendFence = mock(EmailVerificationSendFence.class);
        when(sendFence.acquireForDispatch(any())).thenReturn(() -> {});
        properties = properties();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void stopHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    @Test
    void successfulDeliveryUsesDecryptedOtpAndMarksSent() {
        var job = job(0, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(eq(job.encryptedPayload()), any())).thenReturn(OTP);
        when(store.markSent(job.id(), "worker-1", NOW)).thenReturn(true);

        assertEquals(1, dispatcher(NOW).dispatchDueBatch());

        var message = ArgumentCaptor.forClass(VerificationEmailMessage.class);
        verify(delivery).send(message.capture());
        assertEquals(job.exactRecipientEmail(), message.getValue().exactRecipientEmail());
        assertEquals(OTP.value(), message.getValue().otp().value());
        assertEquals(job.otpExpiresAt(), message.getValue().otpExpiresAt());
        verify(store).markSent(job.id(), "worker-1", NOW);
    }

    @Test
    void sentPersistenceFailureLeavesClaimForLeaseRecoveryWithoutSmtpMisclassification() {
        var job = job(0, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(eq(job.encryptedPayload()), any())).thenReturn(OTP);
        when(store.markSent(job.id(), "worker-1", NOW)).thenThrow(new IllegalStateException("database detail"));

        assertEquals(1, dispatcher(NOW).dispatchDueBatch());

        verify(delivery).send(any());
        verify(store, never()).scheduleRetry(any(), any(), any(), any());
        verify(store, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void expiredJobDoesNotDecryptOrSendAndBecomesExpired() {
        var job = job(0, NOW);
        prepare(job);
        when(store.markExpired(job.id(), "worker-1", NOW)).thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(cipher, never()).decrypt(any(), any());
        verify(delivery, never()).send(any());
        verify(store).markExpired(job.id(), "worker-1", NOW);
    }

    @Test
    void transientFailureSchedulesBoundedRetryAndPreservesPayloadInStoreOperation() {
        var job = job(0, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(any(), any())).thenReturn(OTP);
        org.mockito.Mockito.doThrow(new VerificationEmailDeliveryException(
                        VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE))
                .when(delivery)
                .send(any());
        when(store.scheduleRetry(job.id(), "worker-1", "SMTP_TRANSIENT_FAILURE", NOW.plusSeconds(30)))
                .thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(store).scheduleRetry(job.id(), "worker-1", "SMTP_TRANSIENT_FAILURE", NOW.plusSeconds(30));
        verify(store, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void fifthTransientAttemptTerminatesWithoutAnotherRetry() {
        var job = job(4, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(any(), any())).thenReturn(OTP);
        org.mockito.Mockito.doThrow(new VerificationEmailDeliveryException(
                        VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE))
                .when(delivery)
                .send(any());
        when(store.markFailed(job.id(), "worker-1", "SMTP_TRANSIENT_FAILURE", NOW))
                .thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(store).markFailed(job.id(), "worker-1", "SMTP_TRANSIENT_FAILURE", NOW);
        verify(store, never()).scheduleRetry(any(), any(), any(), any());
    }

    @Test
    void persistedAttemptAtConfiguredMaximumFailsWithoutAnotherDispatchAttempt() {
        var job = job(5, NOW.plusSeconds(600));
        prepare(job);
        when(store.markAttemptsExhausted(job.id(), "worker-1", NOW)).thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(cipher, never()).decrypt(any(), any());
        verify(delivery, never()).send(any());
        verify(store).markAttemptsExhausted(job.id(), "worker-1", NOW);
    }

    @Test
    void permanentSmtpFailureTerminatesImmediately() {
        var job = job(0, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(any(), any())).thenReturn(OTP);
        org.mockito.Mockito.doThrow(new VerificationEmailDeliveryException(
                        VerificationEmailDeliveryException.Category.SMTP_PERMANENT_FAILURE))
                .when(delivery)
                .send(any());
        when(store.markFailed(job.id(), "worker-1", "SMTP_PERMANENT_FAILURE", NOW))
                .thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(store).markFailed(job.id(), "worker-1", "SMTP_PERMANENT_FAILURE", NOW);
    }

    @Test
    void missingHistoricalKeyRetriesWithoutSmtp() {
        var job = job(0, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(any(), any()))
                .thenThrow(new OutboxPayloadDecryptionException(OutboxPayloadDecryptionException.Category.MISSING_KEY));
        when(store.scheduleRetry(job.id(), "worker-1", "KEY_UNAVAILABLE", NOW.plusSeconds(30)))
                .thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(delivery, never()).send(any());
        verify(store).scheduleRetry(job.id(), "worker-1", "KEY_UNAVAILABLE", NOW.plusSeconds(30));
    }

    @Test
    void authenticatedCiphertextFailureTerminatesWithoutSmtp() {
        var job = job(0, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(any(), any()))
                .thenThrow(new OutboxPayloadDecryptionException(
                        OutboxPayloadDecryptionException.Category.AUTHENTICATION_FAILED));
        when(store.markFailed(job.id(), "worker-1", "PAYLOAD_AUTHENTICATION_FAILED", NOW))
                .thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(delivery, never()).send(any());
        verify(store).markFailed(job.id(), "worker-1", "PAYLOAD_AUTHENTICATION_FAILED", NOW);
    }

    @Test
    void malformedDecryptedPayloadFailureTerminatesWithoutSmtp() {
        var job = job(0, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(any(), any()))
                .thenThrow(new OutboxPayloadDecryptionException(
                        OutboxPayloadDecryptionException.Category.DECRYPTION_FAILED));
        when(store.markFailed(job.id(), "worker-1", "PAYLOAD_DECRYPTION_FAILED", NOW))
                .thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(delivery, never()).send(any());
        verify(store).markFailed(job.id(), "worker-1", "PAYLOAD_DECRYPTION_FAILED", NOW);
    }

    @Test
    void retryAtExpiryTerminalizesAsExpired() {
        var job = job(0, NOW.plusSeconds(30));
        prepare(job);
        when(cipher.decrypt(any(), any())).thenReturn(OTP);
        org.mockito.Mockito.doThrow(new VerificationEmailDeliveryException(
                        VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE))
                .when(delivery)
                .send(any());
        when(store.markExpiredAfterAttempt(job.id(), "worker-1", NOW)).thenReturn(true);

        dispatcher(NOW).dispatchDueBatch();

        verify(store).markExpiredAfterAttempt(job.id(), "worker-1", NOW);
        verify(store, never()).scheduleRetry(any(), any(), any(), any());
    }

    @Test
    void backoffDoublesAndCapsWithoutOverflow() {
        var dispatcher = dispatcher(NOW);
        assertEquals(Duration.ofSeconds(30), dispatcher.retryDelay(1));
        assertEquals(Duration.ofMinutes(1), dispatcher.retryDelay(2));
        assertEquals(Duration.ofMinutes(2), dispatcher.retryDelay(3));
        assertEquals(Duration.ofMinutes(4), dispatcher.retryDelay(4));
        assertEquals(Duration.ofMinutes(5), dispatcher.retryDelay(5));
        assertEquals(Duration.ofMinutes(5), dispatcher.retryDelay(Integer.MAX_VALUE));
    }

    @Test
    void overlappingLocalDispatchIsRejectedToPreserveTheReservedPoolBound() throws Exception {
        CountDownLatch firstDispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstDispatch = new CountDownLatch(1);
        when(store.claimDue("worker-1", NOW, NOW.plusSeconds(60), 20)).thenAnswer(invocation -> {
            firstDispatchEntered.countDown();
            if (!releaseFirstDispatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release first dispatch");
            }
            return List.of();
        });
        var dispatcher = dispatcher(NOW);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(dispatcher::dispatchDueBatch);
            assertTrue(firstDispatchEntered.await(10, TimeUnit.SECONDS));

            assertEquals(0, dispatcher.dispatchDueBatch());

            releaseFirstDispatch.countDown();
            assertEquals(0, first.get(10, TimeUnit.SECONDS));
        } finally {
            releaseFirstDispatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void diagnosticsRedactOtpAndEncryptedBytes() {
        var job = job(0, NOW.plusSeconds(600));
        String diagnostic =
                job + " " + new VerificationEmailMessage("person@gmail.com", "person-a", OTP, job.otpExpiresAt());
        assertFalse(diagnostic.contains(OTP.value()));
        assertFalse(diagnostic.contains("cipher-bytes"));
        assertFalse(diagnostic.contains("nonce-bytes"));
    }

    @Test
    void structuredDispatcherLogsContainNoSensitiveDeliveryMaterial() {
        var job = job(0, NOW.plusSeconds(600));
        prepare(job);
        when(cipher.decrypt(any(), any())).thenReturn(OTP);
        org.mockito.Mockito.doThrow(new VerificationEmailDeliveryException(
                        VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE))
                .when(delivery)
                .send(any());
        when(store.scheduleRetry(job.id(), "worker-1", "SMTP_TRANSIENT_FAILURE", NOW.plusSeconds(30)))
                .thenReturn(true);
        Logger logger = (Logger) LoggerFactory.getLogger(EmailVerificationOutboxDispatcher.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            dispatcher(NOW).dispatchDueBatch();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String diagnostics = appender.list.stream()
                .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(diagnostics.contains(OTP.value()));
        assertFalse(diagnostics.contains("cipher-bytes"));
        assertFalse(diagnostics.contains("nonce-bytes"));
        assertFalse(diagnostics.contains("smtp-password"));
        assertFalse(diagnostics.contains("Verify your Quizopia"));
    }

    private void prepare(ClaimedEmailVerificationOutboxJob job) {
        when(store.claimDue(eq("worker-1"), eq(NOW), eq(NOW.plusSeconds(60)), eq(20)))
                .thenReturn(List.of(job));
        when(store.renewClaim(job.id(), "worker-1", NOW, NOW.plusSeconds(60))).thenReturn(true);
        when(store.reserveAttempt(job.id(), "worker-1", NOW, NOW.plusSeconds(60), 5))
                .thenReturn(OptionalInt.of(job.attemptCount() + 1));
        when(store.isDeliverable(job.id(), "worker-1")).thenReturn(true);
    }

    private EmailVerificationOutboxDispatcher dispatcher(Instant now) {
        return new EmailVerificationOutboxDispatcher(
                store,
                cipher,
                delivery,
                sendFence,
                properties,
                Clock.fixed(now, ZoneOffset.UTC),
                "worker-1",
                heartbeatExecutor);
    }

    private static EmailOutboxDispatcherProperties properties() {
        var value = new EmailOutboxDispatcherProperties();
        value.setEnabled(true);
        value.setFromAddress("no-reply@quizopia.test");
        return value;
    }

    private static ClaimedEmailVerificationOutboxJob job(int attempts, Instant expiresAt) {
        return new ClaimedEmailVerificationOutboxJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "person-a",
                "person@gmail.com",
                EmailVerificationOutboxDispatcher.TEMPLATE_TYPE,
                expiresAt,
                attempts,
                new EncryptedOutboxPayload(
                        "cipher-bytes-more-than-sixteen".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        "nonce-bytes!".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        "test-v1",
                        1));
    }
}
