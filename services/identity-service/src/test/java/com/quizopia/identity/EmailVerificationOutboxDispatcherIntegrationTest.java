package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.quizopia.identity.application.emailverification.DeterministicEmailVerificationTestIssuer;
import com.quizopia.identity.application.emailverification.EmailVerificationIssueStatus;
import com.quizopia.identity.application.emailverification.EmailVerificationPolicy;
import com.quizopia.identity.application.emailverification.EmailVerificationService;
import com.quizopia.identity.application.emailverification.EmailVerificationStatus;
import com.quizopia.identity.application.emailverification.delivery.ClaimedEmailVerificationOutboxJob;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxDispatcher;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxStore;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationSendFence;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDelivery;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDeliveryException;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailMessage;
import com.quizopia.identity.configuration.EmailOutboxDispatcherProperties;
import com.quizopia.identity.infrastructure.email.SpringMailVerificationEmailDelivery;
import com.quizopia.identity.infrastructure.email.VerificationEmailTemplate;
import com.quizopia.identity.persistence.emailverification.EmailVerificationOutboxPersistence;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.outbox.OutboxPayloadBinding;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
@Import(DeterministicEmailVerificationTestIssuer.class)
class EmailVerificationOutboxDispatcherIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-23T04:00:00.123456Z");
    private static final RawEmailVerificationOtp OTP = RawEmailVerificationOtp.from("012345");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 1000);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HikariDataSource primaryDataSource;

    @Autowired
    private EmailVerificationOutboxPersistence enqueuePersistence;

    @Autowired
    private EmailVerificationOutboxStore store;

    @Autowired
    private OutboxPayloadCipher cipher;

    @MockitoSpyBean
    private EmailVerificationSendFence sendFence;

    @Autowired
    private DeterministicEmailVerificationTestIssuer testIssuer;

    @Autowired
    private EmailVerificationService verificationService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("serviceClientPasswordEncoder") private PasswordEncoder passwordEncoder;

    @MockitoBean(name = "identityClock")
    private Clock identityClock;

    private ScheduledExecutorService heartbeatExecutor;

    @BeforeEach
    void clearOutboxFixtures() {
        heartbeatExecutor = Executors.newScheduledThreadPool(2);
        when(identityClock.instant()).thenReturn(NOW);
        jdbc.update("DELETE FROM email_verification_email_outbox");
        jdbc.update("DELETE FROM email_verification_challenge");
        jdbc.update("DELETE FROM email_verification_issuance");
        jdbc.update("DELETE FROM email_verification_issuance_guard");
        jdbc.update("DELETE FROM user_account");
    }

    @AfterEach
    void stopHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    @Test
    void concurrentWorkersClaimExactlyOnce() throws Exception {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedEmailVerificationOutboxJob>> first = executor.submit(() -> {
                release.await(30, TimeUnit.SECONDS);
                return store.claimDue("worker-a", NOW, NOW.plusSeconds(60), 1);
            });
            Future<List<ClaimedEmailVerificationOutboxJob>> second = executor.submit(() -> {
                release.await(30, TimeUnit.SECONDS);
                return store.claimDue("worker-b", NOW, NOW.plusSeconds(60), 1);
            });
            release.countDown();
            int claimed = first.get(30, TimeUnit.SECONDS).size()
                    + second.get(30, TimeUnit.SECONDS).size();
            assertEquals(1, claimed);
            assertEquals("CLAIMED", row(fixture.jobId()).state());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulDispatchRunsSmtpOutsideTransactionAndClearsPayload() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        List<VerificationEmailMessage> delivered = new ArrayList<>();
        VerificationEmailDelivery delivery = message -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            delivered.add(message);
        };

        assertEquals(
                1,
                dispatcher(store, delivery, new MutableClock(NOW), "worker-success")
                        .dispatchDueBatch());

        assertEquals(1, delivered.size());
        assertEquals(fixture.email(), delivered.getFirst().exactRecipientEmail());
        assertEquals(fixture.username(), delivered.getFirst().exactUsername());
        assertEquals(OTP.value(), delivered.getFirst().otp().value());
        assertEquals(fixture.expiresAt(), delivered.getFirst().otpExpiresAt());
        OutboxRow row = row(fixture.jobId());
        assertEquals("SENT", row.state());
        assertEquals(1, row.attemptCount());
        assertNull(row.ciphertext());
        assertNull(row.nonce());
        assertNull(row.claimOwner());
        assertNull(row.failureCategory());
    }

    @Test
    void duplicateRecipientJobsRenderTheUsernameFromEachDurableAccountLink() {
        String sharedEmail = "shared-dispatch-" + UUID.randomUUID() + "@gmail.com";
        JobFixture first = enqueue(NOW.plusSeconds(600), sharedEmail, "account-a-" + UUID.randomUUID());
        JobFixture second = enqueue(NOW.plusSeconds(600), sharedEmail, "account-b-" + UUID.randomUUID());
        List<VerificationEmailMessage> delivered = new ArrayList<>();

        assertEquals(
                2,
                dispatcher(store, delivered::add, new MutableClock(NOW), "worker-context")
                        .dispatchDueBatch());

        assertEquals(
                Set.of(first.username(), second.username()),
                delivered.stream()
                        .map(VerificationEmailMessage::exactUsername)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(sharedEmail),
                delivered.stream()
                        .map(VerificationEmailMessage::exactRecipientEmail)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                2L,
                count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", sharedEmail));
        assertEquals(
                2L,
                count(
                        "SELECT COUNT(DISTINCT user_id) FROM email_verification_email_outbox WHERE recipient_email = ?",
                        sharedEmail));
    }

    @Test
    void transientFailureRetriesTheSameCommittedOtpWithoutIssuanceSideEffects() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        MutableClock clock = new MutableClock(NOW);
        List<String> deliveredOtps = new ArrayList<>();
        VerificationEmailDelivery delivery = message -> {
            deliveredOtps.add(message.otp().value());
            if (deliveredOtps.size() == 1) {
                throw new VerificationEmailDeliveryException(
                        VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE);
            }
        };
        var dispatcher = dispatcher(store, delivery, clock, "worker-retry");

        dispatcher.dispatchDueBatch();
        OutboxRow retry = row(fixture.jobId());
        assertEquals("PENDING", retry.state());
        assertEquals(1, retry.attemptCount());
        assertEquals("SMTP_TRANSIENT_FAILURE", retry.failureCategory());
        assertTrue(retry.ciphertext().length > 16);
        assertEquals(12, retry.nonce().length);

        clock.set(retry.nextAttemptAt());
        dispatcher.dispatchDueBatch();

        assertEquals(List.of(OTP.value(), OTP.value()), deliveredOtps);
        assertEquals("SENT", row(fixture.jobId()).state());
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", fixture.email()));
        assertEquals(
                1L,
                count(
                        "SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?",
                        fixture.email()));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_challenge"));
    }

    @Test
    void fifthTransientFailureTerminatesAndClearsPayload() {
        JobFixture fixture = enqueue(NOW.plusSeconds(3600));
        MutableClock clock = new MutableClock(NOW);
        VerificationEmailDelivery delivery = message -> {
            throw new VerificationEmailDeliveryException(
                    VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE);
        };
        var dispatcher = dispatcher(store, delivery, clock, "worker-max");

        for (int attempt = 1; attempt <= 5; attempt++) {
            dispatcher.dispatchDueBatch();
            OutboxRow current = row(fixture.jobId());
            assertEquals(attempt, current.attemptCount());
            if (attempt < 5) {
                assertEquals("PENDING", current.state());
                clock.set(current.nextAttemptAt());
            }
        }

        OutboxRow failed = row(fixture.jobId());
        assertEquals("FAILED", failed.state());
        assertEquals("SMTP_TRANSIENT_FAILURE", failed.failureCategory());
        assertNull(failed.ciphertext());
        assertNull(failed.nonce());
    }

    @Test
    void expiredOtpNeverDecryptsOrSendsAndClearsPayload() {
        Instant expiry = NOW.plusSeconds(1);
        JobFixture fixture = enqueue(expiry);
        List<VerificationEmailMessage> delivered = new ArrayList<>();

        dispatcher(store, delivered::add, new MutableClock(expiry), "worker-expired")
                .dispatchDueBatch();

        assertTrue(delivered.isEmpty());
        OutboxRow expired = row(fixture.jobId());
        assertEquals("EXPIRED", expired.state());
        assertEquals(0, expired.attemptCount());
        assertEquals("OTP_EXPIRED", expired.failureCategory());
        assertNull(expired.ciphertext());
        assertNull(expired.nonce());
    }

    @Test
    void retryCutOffByExpiryCountsTheFailedAttemptAndClearsPayload() {
        JobFixture fixture = enqueue(NOW.plusSeconds(30));
        VerificationEmailDelivery delivery = message -> {
            throw new VerificationEmailDeliveryException(
                    VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE);
        };

        dispatcher(store, delivery, new MutableClock(NOW), "worker-expiry-cutoff")
                .dispatchDueBatch();

        OutboxRow expired = row(fixture.jobId());
        assertEquals("EXPIRED", expired.state());
        assertEquals(1, expired.attemptCount());
        assertEquals("OTP_EXPIRED", expired.failureCategory());
        assertNull(expired.ciphertext());
        assertNull(expired.nonce());
    }

    @Test
    void configuredMaximumBelowPersistedAttemptCountFailsWithoutSending() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        jdbc.update("UPDATE email_verification_email_outbox SET attempt_count = 5 WHERE id = ?", fixture.jobId());
        List<VerificationEmailMessage> delivered = new ArrayList<>();

        dispatcher(store, delivered::add, new MutableClock(NOW), "worker-lowered-limit")
                .dispatchDueBatch();

        assertTrue(delivered.isEmpty());
        OutboxRow failed = row(fixture.jobId());
        assertEquals("FAILED", failed.state());
        assertEquals(5, failed.attemptCount());
        assertEquals("MAXIMUM_ATTEMPTS_EXHAUSTED", failed.failureCategory());
        assertNull(failed.ciphertext());
        assertNull(failed.nonce());
    }

    @Test
    void missingHistoricalKeyIsRetryableAndPreservesPayload() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        jdbc.update(
                "UPDATE email_verification_email_outbox SET key_version = 'missing-v1' WHERE id = ?", fixture.jobId());
        List<VerificationEmailMessage> delivered = new ArrayList<>();

        dispatcher(store, delivered::add, new MutableClock(NOW), "worker-key").dispatchDueBatch();

        assertTrue(delivered.isEmpty());
        OutboxRow retry = row(fixture.jobId());
        assertEquals("PENDING", retry.state());
        assertEquals(1, retry.attemptCount());
        assertEquals("KEY_UNAVAILABLE", retry.failureCategory());
        assertTrue(retry.ciphertext().length > 16);
        assertEquals(12, retry.nonce().length);
    }

    @Test
    void missingHistoricalKeyAtFifthAttemptFailsAndClearsPayload() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        jdbc.update(
                "UPDATE email_verification_email_outbox SET key_version = 'missing-v1', attempt_count = 4 WHERE id = ?",
                fixture.jobId());
        List<VerificationEmailMessage> delivered = new ArrayList<>();

        dispatcher(store, delivered::add, new MutableClock(NOW), "worker-key-max")
                .dispatchDueBatch();

        assertTrue(delivered.isEmpty());
        OutboxRow failed = row(fixture.jobId());
        assertEquals("FAILED", failed.state());
        assertEquals(5, failed.attemptCount());
        assertEquals("KEY_UNAVAILABLE", failed.failureCategory());
        assertNull(failed.ciphertext());
        assertNull(failed.nonce());
    }

    @Test
    void permanentSpringMailFailurePersistsSanitizedTerminalCleanup() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new MailAuthenticationException("raw provider credential detail"))
                .when(mailSender)
                .send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
        var delivery = new SpringMailVerificationEmailDelivery(
                mailSender, new VerificationEmailTemplate(), "no-reply@quizopia.test");

        dispatcher(store, delivery, new MutableClock(NOW), "worker-permanent").dispatchDueBatch();

        OutboxRow failed = row(fixture.jobId());
        assertEquals("FAILED", failed.state());
        assertEquals(1, failed.attemptCount());
        assertEquals("SMTP_PERMANENT_FAILURE", failed.failureCategory());
        assertNull(failed.ciphertext());
        assertNull(failed.nonce());
        assertFalse(String.valueOf(failed.failureCategory()).contains("credential"));
    }

    @Test
    void ciphertextTamperFailsAuthenticationAndClearsPayload() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        byte[] tampered = row(fixture.jobId()).ciphertext();
        tampered[0] ^= 1;
        jdbc.update(
                "UPDATE email_verification_email_outbox SET ciphertext = ? WHERE id = ?", tampered, fixture.jobId());
        List<VerificationEmailMessage> delivered = new ArrayList<>();

        dispatcher(store, delivered::add, new MutableClock(NOW), "worker-tamper")
                .dispatchDueBatch();

        assertTrue(delivered.isEmpty());
        OutboxRow failed = row(fixture.jobId());
        assertEquals("FAILED", failed.state());
        assertEquals("PAYLOAD_AUTHENTICATION_FAILED", failed.failureCategory());
        assertNull(failed.ciphertext());
        assertNull(failed.nonce());
    }

    @Test
    void staleLeaseRecoversAndCrashAfterSendCanRedeliverSameOtp() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        MutableClock clock = new MutableClock(NOW);
        List<String> deliveries = new ArrayList<>();
        EmailVerificationOutboxStore lostFinalWrite = lostSentPersistence(store);

        dispatcher(lostFinalWrite, message -> deliveries.add(message.otp().value()), clock, "crashed-worker")
                .dispatchDueBatch();
        assertEquals("CLAIMED", row(fixture.jobId()).state());
        assertEquals(1, row(fixture.jobId()).attemptCount());
        assertTrue(store.claimDue("early-worker", NOW.plusSeconds(59), NOW.plusSeconds(119), 1)
                .isEmpty());

        clock.set(NOW.plusSeconds(61));
        dispatcher(store, message -> deliveries.add(message.otp().value()), clock, "recovery-worker")
                .dispatchDueBatch();

        assertEquals(List.of(OTP.value(), OTP.value()), deliveries);
        assertEquals("SENT", row(fixture.jobId()).state());
        assertEquals(2, row(fixture.jobId()).attemptCount());
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", fixture.email()));
        assertEquals(
                1L,
                count(
                        "SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?",
                        fixture.email()));
    }

    @Test
    void repeatedAmbiguousSendsConsumeDurableBudgetAndEventuallyStopSmtp() {
        JobFixture fixture = enqueue(NOW.plusSeconds(900));
        MutableClock clock = new MutableClock(NOW);
        List<String> deliveries = new ArrayList<>();
        EmailVerificationOutboxStore lostFinalWrite = lostSentPersistence(store);

        for (int attempt = 1; attempt <= 5; attempt++) {
            clock.set(NOW.plusSeconds((attempt - 1L) * 61L));
            dispatcher(
                            lostFinalWrite,
                            message -> deliveries.add(message.otp().value()),
                            clock,
                            "ambiguous-worker-" + attempt)
                    .dispatchDueBatch();
            assertEquals(attempt, row(fixture.jobId()).attemptCount());
        }

        clock.set(NOW.plusSeconds(5L * 61L));
        dispatcher(store, message -> deliveries.add(message.otp().value()), clock, "exhaustion-worker")
                .dispatchDueBatch();

        assertEquals(5, deliveries.size());
        OutboxRow exhausted = row(fixture.jobId());
        assertEquals("FAILED", exhausted.state());
        assertEquals(5, exhausted.attemptCount());
        assertEquals("MAXIMUM_ATTEMPTS_EXHAUSTED", exhausted.failureCategory());
        assertNull(exhausted.ciphertext());
        assertNull(exhausted.nonce());
    }

    @Test
    void heartbeatPreventsSecondLiveWorkerClaimDuringSlowSmtp() throws Exception {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        MutableClock clock = new MutableClock(NOW);
        CountDownLatch smtpEntered = new CountDownLatch(1);
        CountDownLatch releaseSmtp = new CountDownLatch(1);
        CountDownLatch advancedHeartbeat = new CountDownLatch(1);
        List<String> deliveries = new ArrayList<>();
        EmailVerificationOutboxStore observedStore = new ForwardingStore(store) {
            @Override
            public boolean renewClaim(UUID jobId, String owner, Instant now, Instant expiresAt) {
                boolean renewed = super.renewClaim(jobId, owner, now, expiresAt);
                if (renewed && now.isAfter(NOW)) {
                    advancedHeartbeat.countDown();
                }
                return renewed;
            }
        };
        VerificationEmailDelivery slowDelivery = message -> {
            deliveries.add(message.otp().value());
            smtpEntered.countDown();
            try {
                if (!releaseSmtp.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release SMTP test call");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SMTP test call was interrupted", exception);
            }
        };
        var worker = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = worker.submit(
                    () -> dispatcher(observedStore, cipher, slowDelivery, clock, "live-worker", Duration.ofMillis(300))
                            .dispatchDueBatch());
            assertTrue(smtpEntered.await(30, TimeUnit.SECONDS));
            clock.set(NOW.plusMillis(400));
            assertTrue(advancedHeartbeat.await(30, TimeUnit.SECONDS));

            assertTrue(store.claimDue("second-worker", NOW.plusMillis(350), NOW.plusSeconds(1), 1)
                    .isEmpty());
            releaseSmtp.countDown();
            assertEquals(1, first.get(30, TimeUnit.SECONDS));
        } finally {
            releaseSmtp.countDown();
            worker.shutdownNow();
        }

        assertEquals(List.of(OTP.value()), deliveries);
        assertEquals("SENT", row(fixture.jobId()).state());
        assertEquals(1, row(fixture.jobId()).attemptCount());
    }

    @Test
    void dedicatedDispatcherPoolKeepsHeartbeatAndFinalizationAvailableWhilePrimaryPoolIsFenced() throws Exception {
        String email = "pool-starvation-" + UUID.randomUUID() + "@gmail.com";
        JobFixture fixture = enqueue(NOW.plusSeconds(600), email, "dispatch-" + UUID.randomUUID());
        UUID firstWaitingUser = insertPendingUser(email);
        UUID secondWaitingUser = insertPendingUser(email);
        CountDownLatch smtpEntered = new CountDownLatch(1);
        CountDownLatch releaseSmtp = new CountDownLatch(1);
        CountDownLatch mutationsReachedFence = new CountDownLatch(2);
        CountDownLatch heartbeatRenewedWhilePrimaryPoolFull = new CountDownLatch(1);
        AtomicBoolean primaryPoolFull = new AtomicBoolean();
        EmailVerificationOutboxStore observedStore = new ForwardingStore(store) {
            @Override
            public boolean renewClaim(UUID jobId, String owner, Instant now, Instant expiresAt) {
                boolean renewed = super.renewClaim(jobId, owner, now, expiresAt);
                if (renewed && primaryPoolFull.get()) {
                    heartbeatRenewedWhilePrimaryPoolFull.countDown();
                }
                return renewed;
            }
        };
        doAnswer(invocation -> {
                    mutationsReachedFence.countDown();
                    return invocation.callRealMethod();
                })
                .when(sendFence)
                .serializeMutation(email);
        VerificationEmailDelivery slowDelivery = message -> {
            smtpEntered.countDown();
            await(releaseSmtp);
        };

        var executor = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> dispatch = executor.submit(() -> dispatcher(
                            observedStore,
                            cipher,
                            slowDelivery,
                            new MutableClock(NOW),
                            "pool-worker",
                            Duration.ofMillis(300))
                    .dispatchDueBatch());
            assertTrue(smtpEntered.await(30, TimeUnit.SECONDS));
            Future<EmailVerificationIssueStatus> firstMutation = executor.submit(() -> testIssuer.issueChallenge(
                    firstWaitingUser,
                    RawEmailVerificationOtp.from("345678"),
                    new EmailVerificationPolicy(Duration.ofMinutes(10), 5, Duration.ZERO)));
            Future<EmailVerificationIssueStatus> secondMutation = executor.submit(() -> testIssuer.issueChallenge(
                    secondWaitingUser,
                    RawEmailVerificationOtp.from("456789"),
                    new EmailVerificationPolicy(Duration.ofMinutes(10), 5, Duration.ZERO)));
            assertTrue(mutationsReachedFence.await(30, TimeUnit.SECONDS));
            assertEquals(2, primaryDataSource.getHikariPoolMXBean().getActiveConnections());
            primaryPoolFull.set(true);
            assertTrue(heartbeatRenewedWhilePrimaryPoolFull.await(30, TimeUnit.SECONDS));

            releaseSmtp.countDown();
            assertEquals(1, dispatch.get(30, TimeUnit.SECONDS));
            assertEquals("SENT", row(fixture.jobId()).state());
            assertEquals(EmailVerificationIssueStatus.ISSUED, firstMutation.get(30, TimeUnit.SECONDS));
            assertEquals(EmailVerificationIssueStatus.ISSUED, secondMutation.get(30, TimeUnit.SECONDS));
        } finally {
            releaseSmtp.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void claimedJobTerminalizedAfterDecryptIsRecheckedBeforeSmtp() {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        List<VerificationEmailMessage> deliveries = new ArrayList<>();
        OutboxPayloadCipher supersedingCipher = new OutboxPayloadCipher() {
            @Override
            public com.quizopia.identity.security.outbox.EncryptedOutboxPayload encrypt(
                    RawEmailVerificationOtp rawOtp, OutboxPayloadBinding binding) {
                return cipher.encrypt(rawOtp, binding);
            }

            @Override
            public RawEmailVerificationOtp decrypt(
                    com.quizopia.identity.security.outbox.EncryptedOutboxPayload payload,
                    OutboxPayloadBinding binding) {
                RawEmailVerificationOtp decrypted = cipher.decrypt(payload, binding);
                jdbc.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'FAILED', ciphertext = NULL, nonce = NULL, claim_owner = NULL, "
                                + "claim_expires_at = NULL, terminal_at = ?, failure_category = 'OTP_SUPERSEDED' "
                                + "WHERE id = ?",
                        java.sql.Timestamp.from(NOW),
                        fixture.jobId());
                return decrypted;
            }
        };

        dispatcher(
                        store,
                        supersedingCipher,
                        deliveries::add,
                        new MutableClock(NOW),
                        "superseded-worker",
                        Duration.ofMinutes(1))
                .dispatchDueBatch();

        assertTrue(deliveries.isEmpty());
        assertEquals("FAILED", row(fixture.jobId()).state());
        assertEquals("OTP_SUPERSEDED", row(fixture.jobId()).failureCategory());
        assertNull(row(fixture.jobId()).ciphertext());
        assertNull(row(fixture.jobId()).nonce());
    }

    @Test
    void supersessionCannotCommitBetweenFinalDeliverabilityCheckAndSmtpStart() throws Exception {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));

        assertSendFenceOrdering(
                fixture,
                ignored -> testIssuer.issueChallenge(
                        fixture.userId(),
                        RawEmailVerificationOtp.from("678901"),
                        new EmailVerificationPolicy(Duration.ofMinutes(10), 5, Duration.ZERO)),
                EmailVerificationIssueStatus.ISSUED);
    }

    @Test
    void activationCannotCommitBetweenFinalDeliverabilityCheckAndSmtpStart() throws Exception {
        JobFixture fixture = enqueue(NOW.plusSeconds(600));
        jdbc.update(
                "UPDATE email_verification_challenge SET otp_hash = ? WHERE user_id = ?",
                passwordEncoder.encode(OTP.value()),
                fixture.userId());

        assertSendFenceOrdering(
                fixture,
                ignored -> verificationService.verify(fixture.userId(), OTP),
                EmailVerificationStatus.VERIFIED);
    }

    private <T> void assertSendFenceOrdering(JobFixture fixture, Function<Void, T> mutation, T expected)
            throws Exception {
        CountDownLatch finalCheckPassed = new CountDownLatch(1);
        CountDownLatch allowDispatcherPastCheck = new CountDownLatch(1);
        CountDownLatch mutationReachedFence = new CountDownLatch(1);
        CountDownLatch smtpStarted = new CountDownLatch(1);
        CountDownLatch releaseSmtp = new CountDownLatch(1);
        EmailVerificationOutboxStore observedStore = new ForwardingStore(store) {
            @Override
            public boolean isDeliverable(UUID jobId, String owner) {
                boolean deliverable = super.isDeliverable(jobId, owner);
                if (deliverable) {
                    finalCheckPassed.countDown();
                    await(allowDispatcherPastCheck);
                }
                return deliverable;
            }
        };
        doAnswer(invocation -> {
                    mutationReachedFence.countDown();
                    return invocation.callRealMethod();
                })
                .when(sendFence)
                .serializeMutation(fixture.email());
        VerificationEmailDelivery delivery = message -> {
            smtpStarted.countDown();
            await(releaseSmtp);
        };

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> dispatch = executor.submit(
                    () -> dispatcher(observedStore, delivery, new MutableClock(NOW), "fenced-dispatcher")
                            .dispatchDueBatch());
            assertTrue(finalCheckPassed.await(30, TimeUnit.SECONDS));
            Future<T> mutationResult = executor.submit(() -> mutation.apply(null));
            assertTrue(mutationReachedFence.await(30, TimeUnit.SECONDS));
            assertFalse(mutationResult.isDone());

            allowDispatcherPastCheck.countDown();
            assertTrue(smtpStarted.await(30, TimeUnit.SECONDS));
            assertFalse(mutationResult.isDone());

            releaseSmtp.countDown();
            assertEquals(1, dispatch.get(30, TimeUnit.SECONDS));
            assertEquals(expected, mutationResult.get(30, TimeUnit.SECONDS));
        } finally {
            allowDispatcherPastCheck.countDown();
            releaseSmtp.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for deterministic test barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private EmailVerificationOutboxDispatcher dispatcher(
            EmailVerificationOutboxStore selectedStore, VerificationEmailDelivery delivery, Clock clock, String owner) {
        return dispatcher(selectedStore, cipher, delivery, clock, owner, Duration.ofMinutes(1));
    }

    private EmailVerificationOutboxDispatcher dispatcher(
            EmailVerificationOutboxStore selectedStore,
            OutboxPayloadCipher selectedCipher,
            VerificationEmailDelivery delivery,
            Clock clock,
            String owner,
            Duration leaseDuration) {
        var properties = new EmailOutboxDispatcherProperties();
        properties.setEnabled(true);
        properties.setFromAddress("no-reply@quizopia.test");
        properties.setLeaseDuration(leaseDuration);
        return new EmailVerificationOutboxDispatcher(
                selectedStore, selectedCipher, delivery, sendFence, properties, clock, owner, heartbeatExecutor);
    }

    private JobFixture enqueue(Instant expiresAt) {
        String email = "dispatch-" + UUID.randomUUID() + "@gmail.com";
        String username = "dispatch-" + UUID.randomUUID();
        return enqueue(expiresAt, email, username);
    }

    private JobFixture enqueue(Instant expiresAt, String email, String username) {
        UUID userId = UUID.randomUUID();
        UUID issuanceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account "
                        + "(id, email, username, account_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId,
                email,
                username,
                "PENDING_EMAIL_VERIFICATION",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbc.update("INSERT INTO email_verification_issuance_guard (email) VALUES (?) ON CONFLICT DO NOTHING", email);
        jdbc.update(
                "INSERT INTO email_verification_issuance (id, email, issued_at) VALUES (?, ?, ?)",
                issuanceId,
                email,
                java.sql.Timestamp.from(NOW));
        jdbc.update(
                "INSERT INTO email_verification_challenge "
                        + "(user_id, otp_hash, issued_at, expires_at, resend_not_before, failed_attempts, "
                        + "max_attempts, current_issuance_id) VALUES (?, ?, ?, ?, ?, 0, 5, ?)",
                userId,
                "test-hash",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(expiresAt),
                java.sql.Timestamp.from(NOW),
                issuanceId);
        var binding = new OutboxPayloadBinding(jobId, email, "EMAIL_VERIFICATION_OTP", expiresAt, 1);
        enqueuePersistence.enqueue(
                jobId,
                issuanceId,
                userId,
                email,
                "EMAIL_VERIFICATION_OTP",
                expiresAt,
                NOW,
                cipher.encrypt(OTP, binding));
        return new JobFixture(jobId, issuanceId, userId, username, email, expiresAt);
    }

    private UUID insertPendingUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account "
                        + "(id, email, username, account_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId,
                email,
                "waiting-" + UUID.randomUUID(),
                "PENDING_EMAIL_VERIFICATION",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return userId;
    }

    private EmailVerificationOutboxStore lostSentPersistence(EmailVerificationOutboxStore delegate) {
        return new ForwardingStore(delegate) {
            @Override
            public boolean markSent(UUID jobId, String owner, Instant sentAt) {
                return false;
            }
        };
    }

    private OutboxRow row(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT state, attempt_count, next_attempt_at, claim_owner, ciphertext, nonce, failure_category "
                        + "FROM email_verification_email_outbox WHERE id = ?",
                (result, rowNumber) -> new OutboxRow(
                        result.getString("state"),
                        result.getInt("attempt_count"),
                        result.getTimestamp("next_attempt_at").toInstant(),
                        result.getString("claim_owner"),
                        result.getBytes("ciphertext"),
                        result.getBytes("nonce"),
                        result.getString("failure_category")),
                jobId);
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private record JobFixture(
            UUID jobId, UUID issuanceId, UUID userId, String username, String email, Instant expiresAt) {}

    private record OutboxRow(
            String state,
            int attemptCount,
            Instant nextAttemptAt,
            String claimOwner,
            byte[] ciphertext,
            byte[] nonce,
            String failureCategory) {}

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static class ForwardingStore implements EmailVerificationOutboxStore {
        private final EmailVerificationOutboxStore delegate;

        private ForwardingStore(EmailVerificationOutboxStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ClaimedEmailVerificationOutboxJob> claimDue(
                String owner, Instant now, Instant expiresAt, int batchSize) {
            return delegate.claimDue(owner, now, expiresAt, batchSize);
        }

        @Override
        public boolean renewClaim(UUID jobId, String owner, Instant now, Instant expiresAt) {
            return delegate.renewClaim(jobId, owner, now, expiresAt);
        }

        @Override
        public java.util.OptionalInt reserveAttempt(
                UUID jobId, String owner, Instant now, Instant expiresAt, int maximumAttempts) {
            return delegate.reserveAttempt(jobId, owner, now, expiresAt, maximumAttempts);
        }

        @Override
        public boolean isDeliverable(UUID jobId, String owner) {
            return delegate.isDeliverable(jobId, owner);
        }

        @Override
        public boolean markSent(UUID jobId, String owner, Instant sentAt) {
            return delegate.markSent(jobId, owner, sentAt);
        }

        @Override
        public boolean scheduleRetry(UUID jobId, String owner, String category, Instant nextAttemptAt) {
            return delegate.scheduleRetry(jobId, owner, category, nextAttemptAt);
        }

        @Override
        public boolean markFailed(UUID jobId, String owner, String category, Instant terminalAt) {
            return delegate.markFailed(jobId, owner, category, terminalAt);
        }

        @Override
        public boolean markAttemptsExhausted(UUID jobId, String owner, Instant terminalAt) {
            return delegate.markAttemptsExhausted(jobId, owner, terminalAt);
        }

        @Override
        public boolean markExpired(UUID jobId, String owner, Instant terminalAt) {
            return delegate.markExpired(jobId, owner, terminalAt);
        }

        @Override
        public boolean markExpiredAfterAttempt(UUID jobId, String owner, Instant terminalAt) {
            return delegate.markExpiredAfterAttempt(jobId, owner, terminalAt);
        }

        @Override
        public boolean markObsolete(UUID jobId, String owner, Instant terminalAt) {
            return delegate.markObsolete(jobId, owner, terminalAt);
        }
    }
}
