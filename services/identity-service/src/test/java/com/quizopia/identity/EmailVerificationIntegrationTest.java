package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.activation.TrustedEmailActivationInput;
import com.quizopia.identity.application.activation.TrustedEmailActivationService;
import com.quizopia.identity.application.emailverification.DeterministicEmailVerificationTestIssuer;
import com.quizopia.identity.application.emailverification.EmailVerificationIssueStatus;
import com.quizopia.identity.application.emailverification.EmailVerificationPolicy;
import com.quizopia.identity.application.emailverification.EmailVerificationRequestService;
import com.quizopia.identity.application.emailverification.EmailVerificationRequestStatus;
import com.quizopia.identity.application.emailverification.EmailVerificationService;
import com.quizopia.identity.application.emailverification.EmailVerificationStatus;
import com.quizopia.identity.application.emailverification.EmailVerificationTimingProtector;
import com.quizopia.identity.application.registration.LocalRegistrationInput;
import com.quizopia.identity.application.registration.LocalRegistrationService;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.emailverification.EmailVerificationOtpGenerator;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.outbox.EncryptedOutboxPayload;
import com.quizopia.identity.security.outbox.OutboxPayloadBinding;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.quizopia.identity.security.outbox.OutboxPayloadEncryptionException;
import com.quizopia.identity.security.password.RawLocalPassword;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
@Import(DeterministicEmailVerificationTestIssuer.class)
class EmailVerificationIntegrationTest {
    // All OTP material and policy numbers in this class are test fixtures only.
    private static final Instant ISSUED_AT = Instant.parse("2026-09-08T10:00:00Z");
    private static final RawEmailVerificationOtp OTP = RawEmailVerificationOtp.from("012345");
    private static final RawEmailVerificationOtp REPLACEMENT = RawEmailVerificationOtp.from("678901");
    private static final RawEmailVerificationOtp WRONG = RawEmailVerificationOtp.from("111111");
    private static final EmailVerificationPolicy POLICY_A =
            new EmailVerificationPolicy(Duration.ofMinutes(7), 3, Duration.ofSeconds(11));
    private static final EmailVerificationPolicy POLICY_B =
            new EmailVerificationPolicy(Duration.ofMinutes(2), 5, Duration.ofSeconds(19));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private EmailVerificationService service;

    @Autowired
    private DeterministicEmailVerificationTestIssuer testIssuer;

    @Autowired
    private EmailVerificationRequestService requestService;

    @Autowired
    private LocalRegistrationService registrationService;

    @Autowired
    private TrustedEmailActivationService activationService;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserRoleRepository userRoles;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("serviceClientPasswordEncoder") private PasswordEncoder encoder;

    @MockitoBean(name = "identityClock")
    private Clock clock;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private EmailVerificationOtpGenerator otpGenerator;

    @MockitoSpyBean
    private OutboxPayloadCipher outboxPayloadCipher;

    @MockitoSpyBean
    private EmailVerificationTimingProtector timingProtector;

    @BeforeEach
    void setServerTime() {
        when(clock.instant()).thenReturn(ISSUED_AT);
    }

    @AfterEach
    void noUnrelatedSecuritySideEffects() {
        for (String table : List.of(
                "external_provider_identity",
                "refresh_token_family",
                "refresh_token",
                "user_access_revocation",
                "oauth2_service_client")) {
            assertEquals(0L, count("SELECT COUNT(*) FROM " + table), table);
        }
        verifyNoInteractions(redisTemplate);
    }

    static Stream<EmailVerificationPolicy> explicitPolicies() {
        return Stream.of(POLICY_A, POLICY_B);
    }

    @Test
    void productionRequestAtomicallyPersistsHashIssuanceAndEncryptedOutboxWithoutSmtp() {
        UUID userId = register();
        String email = account(userId).getEmail();
        when(otpGenerator.generate()).thenReturn(OTP);

        assertEquals(EmailVerificationRequestStatus.REQUEST_ACCEPTED, requestService.request(userId));

        Challenge challenge = challenge(userId);
        assertEquals(ISSUED_AT.plus(Duration.ofMinutes(10)), challenge.expiresAt());
        assertEquals(ISSUED_AT.plus(Duration.ofSeconds(60)), challenge.resendNotBefore());
        assertEquals(5, challenge.maxAttempts());
        assertTrue(encoder.matches(OTP.value(), challenge.hash()));
        assertNoRawMaterial(userId);
        String issuanceHistory = jdbc.queryForObject(
                "SELECT row_to_json(issuance)::text FROM email_verification_issuance issuance WHERE email = ?",
                String.class,
                email);
        assertFalse(issuanceHistory.contains(OTP.value()));
        OutboxJob outbox = outbox(email);
        assertEquals("PENDING", outbox.state());
        assertEquals(0, outbox.attemptCount());
        assertEquals("EMAIL_VERIFICATION_OTP", outbox.templateType());
        assertEquals(challenge.expiresAt(), outbox.otpExpiresAt());
        assertEquals("test-v1", outbox.payload().keyVersion());
        assertEquals(1, outbox.payload().payloadFormatVersion());
        assertEquals(12, outbox.payload().nonce().length);
        assertFalse(new String(outbox.payload().ciphertext(), java.nio.charset.StandardCharsets.US_ASCII)
                .contains(OTP.value()));
        assertEquals(
                OTP.value(),
                outboxPayloadCipher
                        .decrypt(
                                outbox.payload(),
                                new OutboxPayloadBinding(
                                        outbox.id(),
                                        email,
                                        outbox.templateType(),
                                        outbox.otpExpiresAt(),
                                        outbox.payload().payloadFormatVersion()))
                        .value());
        assertEquals(
                1L, count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", email));
    }

    @Test
    void postgresqlRoundTripPreservesCanonicalAadExpiryForRestartDecryption() {
        Instant subMicrosecondNow = ISSUED_AT.plusNanos(123_456_789);
        when(clock.instant()).thenReturn(subMicrosecondNow);
        UUID userId = register();
        String email = account(userId).getEmail();
        when(otpGenerator.generate()).thenReturn(OTP);

        assertEquals(EmailVerificationRequestStatus.REQUEST_ACCEPTED, requestService.request(userId));

        OutboxJob reloaded = outbox(email);
        assertEquals(0, reloaded.otpExpiresAt().getNano() % 1_000);
        assertEquals(
                OTP.value(),
                outboxPayloadCipher
                        .decrypt(
                                reloaded.payload(),
                                new OutboxPayloadBinding(
                                        reloaded.id(),
                                        email,
                                        reloaded.templateType(),
                                        reloaded.otpExpiresAt(),
                                        reloaded.payload().payloadFormatVersion()))
                        .value());
    }

    @Test
    void encryptionFailureRollsBackChallengeIssuanceAndOutbox() {
        UUID userId = register();
        String email = account(userId).getEmail();
        when(otpGenerator.generate()).thenReturn(OTP);
        doThrow(new OutboxPayloadEncryptionException())
                .when(outboxPayloadCipher)
                .encrypt(any(), any());

        OutboxPayloadEncryptionException exception =
                assertThrows(OutboxPayloadEncryptionException.class, () -> requestService.request(userId));

        assertFalse(exception.toString().contains(OTP.value()));
        assertEquals(0L, challengeCount(userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", email));
        assertEquals(
                0L, count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", email));
        assertPending(userId);
    }

    @Test
    void outboxPersistenceFailureRollsBackChallengeAndIssuanceHistory() {
        UUID userId = register();
        String email = account(userId).getEmail();
        jdbc.execute(
                "CREATE FUNCTION fail_test_outbox_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test outbox failure'; END $$");
        jdbc.execute(
                "CREATE TRIGGER test_outbox_failure BEFORE INSERT ON email_verification_email_outbox FOR EACH ROW EXECUTE FUNCTION fail_test_outbox_insert()");
        try {
            assertThrows(RuntimeException.class, () -> testIssuer.issueChallenge(userId, OTP, POLICY_A));
        } finally {
            jdbc.execute("DROP TRIGGER test_outbox_failure ON email_verification_email_outbox");
            jdbc.execute("DROP FUNCTION fail_test_outbox_insert()");
        }

        assertEquals(0L, challengeCount(userId));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", email));
        assertEquals(
                0L, count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", email));
        assertPending(userId);

        assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(userId, OTP, POLICY_A));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", email));
        assertEquals(
                1L, count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", email));
    }

    @Test
    void productionRequestDoesNotGenerateOtpBeforeEligibilityOrThrottleChecks() {
        assertEquals(EmailVerificationRequestStatus.NOT_ELIGIBLE, requestService.request(UUID.randomUUID()));
        verifyNoInteractions(otpGenerator);
    }

    @Test
    void productionRequestDoesNotGenerateOrDeliverBeyondHourlyLimit() {
        UUID userId = register();
        String email = account(userId).getEmail();
        when(otpGenerator.generate()).thenReturn(OTP);
        for (int issuance = 0; issuance < 5; issuance++) {
            when(clock.instant()).thenReturn(ISSUED_AT.plusSeconds(issuance * 60L));
            assertEquals(EmailVerificationRequestStatus.REQUEST_ACCEPTED, requestService.request(userId));
        }

        when(clock.instant()).thenReturn(ISSUED_AT.plusSeconds(5 * 60L));
        assertEquals(EmailVerificationRequestStatus.TRY_LATER, requestService.request(userId));
        verify(otpGenerator, times(5)).generate();
        assertEquals(
                5L, count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", email));
    }

    @ParameterizedTest
    @MethodSource("explicitPolicies")
    void issuancePersistsOnlyHashAndExplicitPolicyWithoutActivation(EmailVerificationPolicy policy) {
        UUID userId = register();
        String credential = credential(userId);
        assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(userId, OTP, policy));

        Challenge challenge = challenge(userId);
        assertTrue(challenge.hash().startsWith("{bcrypt}"));
        assertTrue(encoder.matches(OTP.value(), challenge.hash()));
        assertNotEquals(OTP.value(), challenge.hash());
        assertEquals(ISSUED_AT, challenge.issuedAt());
        assertEquals(ISSUED_AT.plus(policy.expiry()), challenge.expiresAt());
        assertEquals(ISSUED_AT.plus(policy.resendCooldown()), challenge.resendNotBefore());
        assertEquals(0, challenge.failedAttempts());
        assertEquals(policy.maxAttempts(), challenge.maxAttempts());
        assertNoRawMaterial(userId);
        assertPending(userId);
        assertEquals(credential, credential(userId));
    }

    @Test
    void unknownUserIsNotCreatedByIssuanceOrVerification() {
        long before = accounts.count();
        UUID unknown = UUID.randomUUID();
        assertEquals(EmailVerificationIssueStatus.NOT_FOUND, testIssuer.issueChallenge(unknown, OTP, POLICY_A));
        assertEquals(EmailVerificationStatus.NOT_FOUND, service.verify(unknown, OTP));
        assertEquals(before, accounts.count());
        assertEquals(0L, challengeCount(unknown));
    }

    @Test
    void pendingUserWithoutChallengeIsSafe() {
        UUID userId = register();
        assertEquals(EmailVerificationStatus.NO_ACTIVE_CHALLENGE, service.verify(userId, OTP));
        verify(timingProtector).balanceConfirmation(OTP);
        assertPending(userId);
    }

    @Test
    void alreadyVerifiedAccountPreservesExistingTimestampAndChallenge() {
        UUID userId = issue(POLICY_A);
        Challenge before = challenge(userId);
        Instant verifiedAt = ISSUED_AT.plusSeconds(1);
        activationService.activate(new TrustedEmailActivationInput(userId, verifiedAt));
        when(clock.instant()).thenReturn(ISSUED_AT.plus(POLICY_A.resendCooldown()));

        assertEquals(
                EmailVerificationIssueStatus.ALREADY_VERIFIED,
                testIssuer.issueChallenge(userId, REPLACEMENT, POLICY_B));
        assertEquals(EmailVerificationStatus.ALREADY_VERIFIED, service.verify(userId, WRONG));
        assertEquals(before, challenge(userId));
        assertEquals(verifiedAt, account(userId).getEmailVerifiedAt());
        assertEquals(Set.of(UserRole.STUDENT), roles(userId));
    }

    @ParameterizedTest
    @CsvSource(
            value = {"PENDING_EMAIL_VERIFICATION,true", "ACTIVE,false", "DISABLED,false", "DISABLED,true", "NULL,false"
            },
            nullValues = "NULL")
    void inconsistentOrUnsupportedAccountStateFailsWithoutRepair(String status, boolean verified) {
        UUID userId = issue(POLICY_A);
        UserAccountEntity account = account(userId);
        account.setAccountStatus(status);
        account.setEmailVerifiedAt(verified ? ISSUED_AT.minusSeconds(1) : null);
        accounts.saveAndFlush(account);
        Challenge before = challenge(userId);
        assertEquals(EmailVerificationIssueStatus.CONFLICT, testIssuer.issueChallenge(userId, REPLACEMENT, POLICY_B));
        assertEquals(EmailVerificationStatus.CONFLICT, service.verify(userId, OTP));
        assertEquals(before, challenge(userId));
        assertEquals(status, account(userId).getAccountStatus());
        assertEquals(account.getEmailVerifiedAt(), account(userId).getEmailVerifiedAt());
        assertTrue(roles(userId).isEmpty());
    }

    @Test
    void cooldownRejectionPreservesEntireChallengeIncludingFailures() {
        UUID userId = issue(POLICY_A);
        assertEquals(EmailVerificationStatus.INVALID_OTP, service.verify(userId, WRONG));
        Challenge before = challenge(userId);
        when(clock.instant()).thenReturn(before.resendNotBefore().minusNanos(1));
        assertEquals(EmailVerificationIssueStatus.COOLDOWN, testIssuer.issueChallenge(userId, REPLACEMENT, POLICY_B));
        assertEquals(before, challenge(userId));
        assertPending(userId);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void resendAtOrAfterBoundaryReplacesPolicyAndInvalidatesPreviousOtp(long secondsAfterBoundary) {
        UUID userId = issue(POLICY_A);
        UUID supersededJobId = outbox(account(userId).getEmail()).id();
        service.verify(userId, WRONG);
        Challenge before = challenge(userId);
        Instant resendAt = before.resendNotBefore().plusSeconds(secondsAfterBoundary);
        when(clock.instant()).thenReturn(resendAt);
        assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(userId, REPLACEMENT, POLICY_B));
        Challenge after = challenge(userId);
        assertNotEquals(before.hash(), after.hash());
        assertTrue(encoder.matches(REPLACEMENT.value(), after.hash()));
        assertFalse(encoder.matches(OTP.value(), after.hash()));
        assertEquals(0, after.failedAttempts());
        assertEquals(POLICY_B.maxAttempts(), after.maxAttempts());
        assertEquals(resendAt, after.issuedAt());
        assertEquals(resendAt.plus(POLICY_B.expiry()), after.expiresAt());
        assertEquals(resendAt.plus(POLICY_B.resendCooldown()), after.resendNotBefore());
        assertEquals(1L, challengeCount(userId));
        UUID currentJobId = outbox(account(userId).getEmail()).id();
        assertEquals("FAILED", text("SELECT state FROM email_verification_email_outbox WHERE id = ?", supersededJobId));
        assertEquals(
                "OTP_SUPERSEDED",
                text("SELECT failure_category FROM email_verification_email_outbox WHERE id = ?", supersededJobId));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT ciphertext IS NULL AND nonce IS NULL FROM email_verification_email_outbox WHERE id = ?",
                Boolean.class,
                supersededJobId)));
        assertEquals(EmailVerificationStatus.INVALID_OTP, service.verify(userId, OTP));
        assertEquals(EmailVerificationStatus.VERIFIED, service.verify(userId, REPLACEMENT));
        assertEquals("FAILED", text("SELECT state FROM email_verification_email_outbox WHERE id = ?", currentJobId));
        assertEquals(
                "OTP_CONSUMED",
                text("SELECT failure_category FROM email_verification_email_outbox WHERE id = ?", currentJobId));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT ciphertext IS NULL AND nonce IS NULL FROM email_verification_email_outbox WHERE id = ?",
                Boolean.class,
                currentJobId)));
        assertEquals(
                0L,
                count(
                        "SELECT COUNT(*) FROM email_verification_email_outbox WHERE user_id = ? AND state IN ('PENDING', 'CLAIMED')",
                        userId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentFirstIssuanceAndResendsLeaveOneWinningChallenge(boolean resend) throws Exception {
        UUID userId = resend ? issue(POLICY_A) : register();
        if (resend) {
            when(clock.instant()).thenReturn(ISSUED_AT.plus(POLICY_A.resendCooldown()));
        }
        List<EmailVerificationIssueStatus> results = concurrently(List.of(
                () -> testIssuer.issueChallenge(userId, OTP, POLICY_B),
                () -> testIssuer.issueChallenge(userId, REPLACEMENT, POLICY_B)));
        assertEquals(
                1,
                results.stream()
                        .filter(s -> s == EmailVerificationIssueStatus.ISSUED)
                        .count());
        assertEquals(
                1,
                results.stream()
                        .filter(s -> s == EmailVerificationIssueStatus.COOLDOWN)
                        .count());
        RawEmailVerificationOtp winner = results.get(0) == EmailVerificationIssueStatus.ISSUED ? OTP : REPLACEMENT;
        RawEmailVerificationOtp loser = winner == OTP ? REPLACEMENT : OTP;
        assertEquals(1L, challengeCount(userId));
        assertTrue(encoder.matches(winner.value(), challenge(userId).hash()));
        assertFalse(encoder.matches(loser.value(), challenge(userId).hash()));
        assertEquals(
                resend ? 2L : 1L,
                count(
                        "SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?",
                        account(userId).getEmail()));
        assertPending(userId);
    }

    @Test
    void zeroCooldownStillSerializesConcurrentReplacements() throws Exception {
        EmailVerificationPolicy policy =
                new EmailVerificationPolicy(POLICY_B.expiry(), POLICY_B.maxAttempts(), Duration.ZERO);
        UUID userId = issue(policy);
        List<EmailVerificationIssueStatus> results = concurrently(List.of(
                () -> testIssuer.issueChallenge(userId, OTP, policy),
                () -> testIssuer.issueChallenge(userId, REPLACEMENT, policy)));
        assertEquals(List.of(EmailVerificationIssueStatus.ISSUED, EmailVerificationIssueStatus.ISSUED), results);
        assertEquals(1L, challengeCount(userId));
        assertEquals(
                3L,
                count(
                        "SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?",
                        account(userId).getEmail()));
        String hash = challenge(userId).hash();
        assertTrue(encoder.matches(OTP.value(), hash) ^ encoder.matches(REPLACEMENT.value(), hash));
    }

    @Test
    void rollingHourlyLimitExcludesAnIssuanceAtTheExactOneHourBoundary() {
        UUID userId = register();
        EmailVerificationPolicy policy = EmailVerificationPolicy.production();
        for (int issuance = 0; issuance < 5; issuance++) {
            when(clock.instant()).thenReturn(ISSUED_AT.plusSeconds(issuance * 60L));
            assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(userId, OTP, policy));
        }
        when(clock.instant()).thenReturn(ISSUED_AT.plusSeconds(5 * 60L));
        assertEquals(EmailVerificationIssueStatus.HOURLY_LIMIT, testIssuer.issueChallenge(userId, OTP, policy));
        assertEquals(
                5L,
                count(
                        "SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?",
                        account(userId).getEmail()));

        when(clock.instant()).thenReturn(ISSUED_AT.plus(Duration.ofHours(1)));
        assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(userId, REPLACEMENT, policy));
        assertEquals(
                6L,
                count(
                        "SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?",
                        account(userId).getEmail()));
        assertEquals(
                5L,
                count(
                        "SELECT COUNT(*) FROM email_verification_issuance "
                                + "WHERE email = ? AND issued_at > ? AND issued_at <= ?",
                        account(userId).getEmail(),
                        java.sql.Timestamp.from(ISSUED_AT),
                        java.sql.Timestamp.from(ISSUED_AT.plus(Duration.ofHours(1)))));
        assertEquals(
                6L,
                count(
                        "SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?",
                        account(userId).getEmail()));
    }

    @Test
    void concurrentDuplicateAccountIssuanceCannotBypassExactEmailHourlyLimit() throws Exception {
        String email = "shared-throttle-" + UUID.randomUUID() + "@gmail.com";
        UUID first = register(email);
        EmailVerificationPolicy policy = EmailVerificationPolicy.production();
        for (int issuance = 0; issuance < 4; issuance++) {
            when(clock.instant()).thenReturn(ISSUED_AT.plusSeconds(issuance * 60L));
            assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(first, OTP, policy));
        }
        UUID second = register(email);
        UUID third = register(email);
        when(clock.instant()).thenReturn(ISSUED_AT.plusSeconds(4 * 60L));

        List<EmailVerificationIssueStatus> results = concurrently(List.of(
                () -> testIssuer.issueChallenge(second, OTP, policy),
                () -> testIssuer.issueChallenge(third, REPLACEMENT, policy)));

        assertEquals(
                1L,
                results.stream()
                        .filter(status -> status == EmailVerificationIssueStatus.ISSUED)
                        .count());
        assertEquals(
                1L,
                results.stream()
                        .filter(status -> status == EmailVerificationIssueStatus.HOURLY_LIMIT)
                        .count());
        assertEquals(5L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", email));
        assertEquals(1L, challengeCount(second) + challengeCount(third));
    }

    @Test
    void wrongOtpPersistsExactlyOneFailureWithoutOtherMutation() {
        UUID userId = issue(POLICY_A);
        Challenge before = challenge(userId);
        assertEquals(EmailVerificationStatus.INVALID_OTP, service.verify(userId, WRONG));
        Challenge after = challenge(userId);
        assertEquals(
                new Challenge(
                        before.hash(),
                        before.issuedAt(),
                        before.expiresAt(),
                        before.resendNotBefore(),
                        1,
                        before.maxAttempts()),
                after);
        assertNoRawMaterial(userId);
        assertPending(userId);
    }

    @ParameterizedTest
    @MethodSource("explicitPolicies")
    void attemptThatReachesLimitExhaustsChallengeAndLaterCorrectOtpFails(EmailVerificationPolicy policy) {
        UUID userId = issue(policy);
        for (int i = 1; i <= policy.maxAttempts(); i++) {
            assertEquals(
                    i == policy.maxAttempts()
                            ? EmailVerificationStatus.ATTEMPTS_EXHAUSTED
                            : EmailVerificationStatus.INVALID_OTP,
                    service.verify(userId, WRONG));
            assertEquals(i, challenge(userId).failedAttempts());
        }
        Challenge exhausted = challenge(userId);
        assertEquals(EmailVerificationStatus.ATTEMPTS_EXHAUSTED, service.verify(userId, OTP));
        assertEquals(EmailVerificationStatus.ATTEMPTS_EXHAUSTED, service.verify(userId, WRONG));
        assertEquals(exhausted, challenge(userId));
        assertPending(userId);
    }

    @Test
    void concurrentWrongAttemptsCannotLoseIncrements() throws Exception {
        UUID userId = issue(POLICY_B);
        List<EmailVerificationStatus> results = concurrently(List.of(
                () -> service.verify(userId, WRONG),
                () -> service.verify(userId, WRONG),
                () -> service.verify(userId, WRONG)));
        assertEquals(
                List.of(
                        EmailVerificationStatus.INVALID_OTP,
                        EmailVerificationStatus.INVALID_OTP,
                        EmailVerificationStatus.INVALID_OTP),
                results);
        assertEquals(3, challenge(userId).failedAttempts());
        assertPending(userId);
    }

    @Test
    void concurrentWrongAttemptsAtLimitCannotOverrunOrReactivate() throws Exception {
        UUID userId = issue(POLICY_A);
        List<Callable<EmailVerificationStatus>> attempts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            attempts.add(() -> service.verify(userId, WRONG));
        }
        List<EmailVerificationStatus> results = concurrently(attempts);
        assertEquals(
                2,
                results.stream()
                        .filter(s -> s == EmailVerificationStatus.INVALID_OTP)
                        .count());
        assertEquals(
                3,
                results.stream()
                        .filter(s -> s == EmailVerificationStatus.ATTEMPTS_EXHAUSTED)
                        .count());
        assertEquals(POLICY_A.maxAttempts(), challenge(userId).failedAttempts());
        assertEquals(EmailVerificationStatus.ATTEMPTS_EXHAUSTED, service.verify(userId, OTP));
        assertPending(userId);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void exactExpiryBoundaryAndLaterRejectWithoutMutation(long secondsAfterExpiry) {
        UUID userId = issue(POLICY_A);
        Challenge before = challenge(userId);
        when(clock.instant()).thenReturn(before.expiresAt().plusSeconds(secondsAfterExpiry));
        assertEquals(EmailVerificationStatus.EXPIRED, service.verify(userId, OTP));
        assertEquals(EmailVerificationStatus.EXPIRED, service.verify(userId, WRONG));
        assertEquals(before, challenge(userId));
        assertPending(userId);
    }

    @Test
    void correctOtpImmediatelyBeforeExpiryActivatesAndConsumesChallengeIdempotently() {
        UUID userId = issue(POLICY_A);
        String credential = credential(userId);
        Instant verifiedAt = challenge(userId).expiresAt().minusMillis(1);
        when(clock.instant()).thenReturn(verifiedAt);
        assertEquals(EmailVerificationStatus.VERIFIED, service.verify(userId, OTP));
        assertActivated(userId, verifiedAt, Set.of(UserRole.STUDENT));
        assertEquals(credential, credential(userId));
        when(clock.instant()).thenReturn(verifiedAt.plusSeconds(1));
        assertEquals(EmailVerificationStatus.ALREADY_VERIFIED, service.verify(userId, OTP));
        assertEquals(
                EmailVerificationIssueStatus.ALREADY_VERIFIED,
                testIssuer.issueChallenge(userId, REPLACEMENT, POLICY_B));
        assertActivated(userId, verifiedAt, Set.of(UserRole.STUDENT));
    }

    @Test
    void activationPreservesTeacherAdminAndExistingStudentWithoutDuplicates() {
        UUID userId = issue(POLICY_B);
        UserAccountEntity account = account(userId);
        for (UserRole role : UserRole.values()) {
            userRoles.saveAndFlush(new UserRoleEntity(account, role));
        }
        assertEquals(EmailVerificationStatus.VERIFIED, service.verify(userId, OTP));
        assertActivated(userId, ISSUED_AT, Set.of(UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN));
    }

    @Test
    void activationAddsStudentToExistingTeacherAndAdmin() {
        UUID userId = issue(POLICY_B);
        UserAccountEntity account = account(userId);
        userRoles.saveAndFlush(new UserRoleEntity(account, UserRole.TEACHER));
        userRoles.saveAndFlush(new UserRoleEntity(account, UserRole.ADMIN));
        assertEquals(EmailVerificationStatus.VERIFIED, service.verify(userId, OTP));
        assertActivated(userId, ISSUED_AT, Set.of(UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN));
    }

    @Test
    void verifiedEmailOwnershipConflictIsGenericAndRollsBackTheLosingVerification() {
        String email = "shared-otp-" + UUID.randomUUID() + "@gmail.com";
        UUID firstUserId = register(email);
        UUID secondUserId = register(email);
        assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(firstUserId, OTP, POLICY_A));
        assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(secondUserId, OTP, POLICY_A));

        assertEquals(EmailVerificationStatus.VERIFIED, service.verify(firstUserId, OTP));
        assertEquals(
                "FAILED", text("SELECT state FROM email_verification_email_outbox WHERE user_id = ?", secondUserId));
        assertEquals(
                "EMAIL_OWNERSHIP_LOST",
                text("SELECT failure_category FROM email_verification_email_outbox WHERE user_id = ?", secondUserId));
        assertEquals(
                0L,
                count(
                        "SELECT COUNT(*) FROM email_verification_email_outbox "
                                + "WHERE user_id = ? AND (ciphertext IS NOT NULL OR nonce IS NOT NULL)",
                        secondUserId));
        Challenge losingChallenge = challenge(secondUserId);
        assertEquals(EmailVerificationStatus.CONFLICT, service.verify(secondUserId, OTP));

        assertActivated(firstUserId, ISSUED_AT, Set.of(UserRole.STUDENT));
        assertPending(secondUserId);
        assertEquals(losingChallenge, challenge(secondUserId));
        assertEquals(
                1L,
                count("SELECT COUNT(*) FROM user_account WHERE email = ? AND email_verified_at IS NOT NULL", email));
    }

    @Test
    void verifiedOwnerSuppressesLosingPendingAccountIssuanceWithoutPersistence() {
        String email = "owned-before-request-" + UUID.randomUUID() + "@gmail.com";
        UUID ownerId = register(email);
        UUID losingId = register(email);
        assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(ownerId, OTP, POLICY_A));
        assertEquals(EmailVerificationStatus.VERIFIED, service.verify(ownerId, OTP));
        long historyBefore = count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", email);
        long outboxBefore =
                count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", email);

        assertEquals(EmailVerificationRequestStatus.NOT_ELIGIBLE, requestService.request(losingId));

        assertEquals(0L, challengeCount(losingId));
        assertEquals(historyBefore, count("SELECT COUNT(*) FROM email_verification_issuance WHERE email = ?", email));
        assertEquals(
                outboxBefore,
                count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE recipient_email = ?", email));
        verify(otpGenerator, never()).generate();
    }

    @Test
    void roleInsertFailureAfterMatchRollsBackAccountAndPreservesUsableChallenge() {
        UUID userId = issue(POLICY_A);
        service.verify(userId, WRONG);
        Challenge before = challenge(userId);
        jdbc.execute(
                """
                CREATE FUNCTION fail_test_student_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.user_id = '%s'::uuid AND NEW.role = 'STUDENT' THEN
                        RAISE EXCEPTION 'forced student persistence failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """
                        .formatted(userId));
        jdbc.execute(
                "CREATE TRIGGER test_student_failure BEFORE INSERT ON user_role FOR EACH ROW EXECUTE FUNCTION fail_test_student_insert()");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.verify(userId, OTP));
            assertPending(userId);
            assertEquals(before, challenge(userId));
        } finally {
            jdbc.execute("DROP TRIGGER test_student_failure ON user_role");
            jdbc.execute("DROP FUNCTION fail_test_student_insert()");
        }
        assertEquals(EmailVerificationStatus.VERIFIED, service.verify(userId, OTP));
        assertActivated(userId, ISSUED_AT, Set.of(UserRole.STUDENT));
    }

    @Test
    void challengeDeleteFailureRollsBackAlreadyFlushedActivationAndStudent() {
        UUID userId = issue(POLICY_A);
        Challenge before = challenge(userId);
        jdbc.execute(
                """
                CREATE FUNCTION fail_test_challenge_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.user_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'forced challenge deletion failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN OLD;
                END;
                $$
                """
                        .formatted(userId));
        jdbc.execute(
                "CREATE TRIGGER test_challenge_failure BEFORE DELETE ON email_verification_challenge FOR EACH ROW EXECUTE FUNCTION fail_test_challenge_delete()");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.verify(userId, OTP));
            assertPending(userId);
            assertEquals(before, challenge(userId));
        } finally {
            jdbc.execute("DROP TRIGGER test_challenge_failure ON email_verification_challenge");
            jdbc.execute("DROP FUNCTION fail_test_challenge_delete()");
        }
        assertEquals(EmailVerificationStatus.VERIFIED, service.verify(userId, OTP));
        assertActivated(userId, ISSUED_AT, Set.of(UserRole.STUDENT));
    }

    @Test
    void concurrentCorrectVerificationsActivateExactlyOnce() throws Exception {
        UUID userId = issue(POLICY_A);
        List<EmailVerificationStatus> results =
                concurrently(List.of(() -> service.verify(userId, OTP), () -> service.verify(userId, OTP)));
        assertEquals(
                1,
                results.stream()
                        .filter(s -> s == EmailVerificationStatus.VERIFIED)
                        .count());
        assertEquals(
                1,
                results.stream()
                        .filter(s -> s == EmailVerificationStatus.ALREADY_VERIFIED)
                        .count());
        assertActivated(userId, ISSUED_AT, Set.of(UserRole.STUDENT));
    }

    @Test
    void concurrentResendAndVerificationRespectTheSameLockOrder() throws Exception {
        UUID userId = issue(POLICY_A);
        Instant now = ISSUED_AT.plus(POLICY_A.resendCooldown());
        when(clock.instant()).thenReturn(now);
        List<Object> results = concurrently(List.of(
                () -> testIssuer.issueChallenge(userId, REPLACEMENT, POLICY_B), () -> service.verify(userId, OTP)));
        if (results.get(0) == EmailVerificationIssueStatus.ISSUED) {
            assertEquals(EmailVerificationStatus.INVALID_OTP, results.get(1));
            assertEquals(1, challenge(userId).failedAttempts());
            assertTrue(encoder.matches(REPLACEMENT.value(), challenge(userId).hash()));
            assertPending(userId);
        } else {
            assertEquals(EmailVerificationIssueStatus.ALREADY_VERIFIED, results.get(0));
            assertEquals(EmailVerificationStatus.VERIFIED, results.get(1));
            assertActivated(userId, now, Set.of(UserRole.STUDENT));
        }
    }

    @Test
    void verificationReadsTimeAfterWaitingForTheUserLock() throws Exception {
        UUID userId = issue(POLICY_A);
        Instant expiresAt = challenge(userId).expiresAt();
        CountDownLatch ready = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<EmailVerificationStatus> result = new TransactionTemplate(transactionManager).execute(status -> {
                accounts.findByIdForUpdate(userId).orElseThrow();
                Future<EmailVerificationStatus> attempt = executor.submit(() -> {
                    ready.countDown();
                    return service.verify(userId, OTP);
                });
                try {
                    assertTrue(ready.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                when(clock.instant()).thenReturn(expiresAt);
                return attempt;
            });
            assertEquals(EmailVerificationStatus.EXPIRED, result.get(30, TimeUnit.SECONDS));
            assertPending(userId);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void migrationHasOnlyTheMinimalColumnsAndEnforcesTechnicalConstraints() {
        assertEquals(
                List.of(
                        "user_id",
                        "otp_hash",
                        "issued_at",
                        "expires_at",
                        "resend_not_before",
                        "failed_attempts",
                        "max_attempts",
                        "current_issuance_id"),
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'email_verification_challenge' ORDER BY ordinal_position",
                        String.class));
        assertEquals(
                List.of("email"),
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'email_verification_issuance_guard' ORDER BY ordinal_position",
                        String.class));
        assertEquals(
                List.of("id", "email", "issued_at"),
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'email_verification_issuance' ORDER BY ordinal_position",
                        String.class));
        assertEquals(
                List.of(
                        "id",
                        "issuance_id",
                        "recipient_email",
                        "template_type",
                        "otp_expires_at",
                        "state",
                        "attempt_count",
                        "next_attempt_at",
                        "claim_owner",
                        "claim_expires_at",
                        "key_version",
                        "payload_format_version",
                        "ciphertext",
                        "nonce",
                        "created_at",
                        "sent_at",
                        "terminal_at",
                        "failure_category",
                        "user_id"),
                jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'email_verification_email_outbox' ORDER BY ordinal_position",
                        String.class));
        assertTrue(jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'email_verification_email_outbox'",
                        String.class)
                .containsAll(List.of(
                        "ix_email_verification_email_outbox_due", "ix_email_verification_email_outbox_stale_claim")));
        UUID userId = issue(POLICY_A);
        Challenge before = challenge(userId);
        for (String invalidAssignment : List.of(
                "failed_attempts = -1",
                "max_attempts = 0",
                "failed_attempts = max_attempts + 1",
                "expires_at = issued_at",
                "resend_not_before = issued_at - interval '1 second'",
                "otp_hash = ''",
                "user_id = '" + UUID.randomUUID() + "'")) {
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbc.update(
                            "UPDATE email_verification_challenge SET " + invalidAssignment + " WHERE user_id = ?",
                            userId));
        }
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "INSERT INTO email_verification_challenge SELECT * FROM email_verification_challenge WHERE user_id = ?",
                        userId));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "INSERT INTO email_verification_email_outbox "
                                + "SELECT ?, issuance_id, recipient_email, template_type, otp_expires_at, state, attempt_count, "
                                + "next_attempt_at, claim_owner, claim_expires_at, key_version, payload_format_version, ciphertext, nonce, "
                                + "created_at, sent_at, terminal_at, failure_category, user_id "
                                + "FROM email_verification_email_outbox WHERE recipient_email = ?",
                        UUID.randomUUID(),
                        account(userId).getEmail()));
        UUID outboxId = outbox(account(userId).getEmail()).id();
        for (String invalidAssignment : List.of(
                "state = 'UNKNOWN'",
                "attempt_count = -1",
                "key_version = ''",
                "payload_format_version = 0",
                "ciphertext = NULL",
                "nonce = decode('AA==', 'base64')",
                "state = 'CLAIMED'",
                "claim_owner = 'worker'",
                "otp_expires_at = created_at",
                "next_attempt_at = created_at - interval '1 second'",
                "issuance_id = '" + UUID.randomUUID() + "'",
                "user_id = NULL",
                "state = 'SENT'")) {
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbc.update(
                            "UPDATE email_verification_email_outbox SET " + invalidAssignment + " WHERE id = ?",
                            outboxId));
        }
        assertEquals(
                1,
                jdbc.update(
                        "UPDATE email_verification_email_outbox "
                                + "SET state = 'FAILED', ciphertext = NULL, nonce = NULL, terminal_at = created_at, "
                                + "failure_category = 'TEST_TERMINAL' WHERE id = ?",
                        outboxId));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT ciphertext IS NULL AND nonce IS NULL FROM email_verification_email_outbox WHERE id = ?",
                Boolean.class,
                outboxId)));
        assertEquals(before, challenge(userId));
    }

    private UUID register() {
        String identity = UUID.randomUUID().toString();
        return register(identity + "@gmail.com");
    }

    private UUID register(String email) {
        String identity = UUID.randomUUID().toString();
        return registrationService
                .register(new LocalRegistrationInput(
                        "otp-" + identity, email, RawLocalPassword.from("fixture local password")))
                .userId()
                .orElseThrow();
    }

    private UUID issue(EmailVerificationPolicy policy) {
        UUID userId = register();
        assertEquals(EmailVerificationIssueStatus.ISSUED, testIssuer.issueChallenge(userId, OTP, policy));
        return userId;
    }

    private UserAccountEntity account(UUID userId) {
        return accounts.findById(userId).orElseThrow();
    }

    private Set<UserRole> roles(UUID userId) {
        return userRoles.findAllByUser_Id(userId).stream()
                .map(UserRoleEntity::getRole)
                .collect(Collectors.toSet());
    }

    private void assertPending(UUID userId) {
        assertEquals(
                AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION,
                account(userId).getAccountStatus());
        assertNull(account(userId).getEmailVerifiedAt());
        assertTrue(roles(userId).isEmpty());
    }

    private void assertActivated(UUID userId, Instant verifiedAt, Set<UserRole> expectedRoles) {
        assertEquals(AccountLifecycleStatus.ACTIVE, account(userId).getAccountStatus());
        assertEquals(verifiedAt, account(userId).getEmailVerifiedAt());
        assertEquals(expectedRoles, roles(userId));
        assertEquals(1L, count("SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role = 'STUDENT'", userId));
        assertEquals(0L, challengeCount(userId));
    }

    private String credential(UUID userId) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM local_credential WHERE user_id = ?", String.class, userId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private long challengeCount(UUID userId) {
        return count("SELECT COUNT(*) FROM email_verification_challenge WHERE user_id = ?", userId);
    }

    private Challenge challenge(UUID userId) {
        return jdbc.queryForObject(
                "SELECT * FROM email_verification_challenge WHERE user_id = ?",
                (row, rowNum) -> new Challenge(
                        row.getString("otp_hash"),
                        row.getTimestamp("issued_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("resend_not_before").toInstant(),
                        row.getInt("failed_attempts"),
                        row.getInt("max_attempts")),
                userId);
    }

    private OutboxJob outbox(String exactEmail) {
        return jdbc.queryForObject(
                "SELECT * FROM email_verification_email_outbox WHERE recipient_email = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                (row, rowNum) -> new OutboxJob(
                        row.getObject("id", UUID.class),
                        row.getString("template_type"),
                        row.getTimestamp("otp_expires_at").toInstant(),
                        row.getString("state"),
                        row.getInt("attempt_count"),
                        new EncryptedOutboxPayload(
                                row.getBytes("ciphertext"),
                                row.getBytes("nonce"),
                                row.getString("key_version"),
                                row.getInt("payload_format_version"))),
                exactEmail);
    }

    private void assertNoRawMaterial(UUID userId) {
        String persisted = jdbc.queryForObject(
                "SELECT row_to_json(challenge)::text FROM email_verification_challenge challenge WHERE user_id = ?",
                String.class,
                userId);
        for (RawEmailVerificationOtp material : List.of(OTP, REPLACEMENT, WRONG)) {
            assertFalse(persisted.contains(material.value()));
        }
    }

    private static <T> List<T> concurrently(List<Callable<T>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(operations.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out releasing concurrent OTP operations");
                    }
                    return operation.call();
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            release.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private record Challenge(
            String hash,
            Instant issuedAt,
            Instant expiresAt,
            Instant resendNotBefore,
            int failedAttempts,
            int maxAttempts) {}

    private record OutboxJob(
            UUID id,
            String templateType,
            Instant otpExpiresAt,
            String state,
            int attemptCount,
            EncryptedOutboxPayload payload) {}
}
