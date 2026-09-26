package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.application.revocation.AuthoritativeRevocationService;
import com.quizopia.identity.application.revocation.AuthoritativeRevocationState;
import com.quizopia.identity.application.revocation.RedisRevocationLookupStatus;
import com.quizopia.identity.application.revocation.RedisRevocationPropagationStatus;
import com.quizopia.identity.application.revocation.RevocationRepublishResult;
import com.quizopia.identity.application.revocation.RevocationWriteResult;
import com.quizopia.identity.infrastructure.revocation.RedisRevocationKey;
import com.quizopia.identity.infrastructure.revocation.RedisRevocationStateStore;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RevocationRedisIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.connect-timeout", () -> "1s");
        registry.add("spring.data.redis.timeout", () -> "1s");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AuthoritativeRevocationService revocationService;

    @Autowired
    private RedisRevocationStateStore redisStateStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @Order(1)
    void flywayCreatesRevocationTableAndRedisStoresOnlyTheCutoff() {
        assertEquals("6", flyway.info().current().getVersion().getVersion());
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'user_access_revocation'",
                Integer.class);
        assertEquals(1, tableCount);
    }

    @Test
    @Order(2)
    void authoritativeCutoffIsPersistedAndPropagated() {
        UUID userId = persistAccount();
        UserAccountEntity before = userAccountRepository.findById(userId).orElseThrow();
        Instant cutoff = Instant.parse("2026-07-08T09:10:11Z");

        RevocationWriteResult result = revocationService.recordRevocation(userId, cutoff);

        assertEquals(cutoff, result.authoritativeState().revokedBefore());
        assertEquals(RedisRevocationPropagationStatus.PROPAGATED, result.propagationStatus());
        assertEquals(cutoff, revocationService.find(userId).orElseThrow().revokedBefore());

        UserAccountEntity after = userAccountRepository.findById(userId).orElseThrow();
        assertEquals(before.getEmail(), after.getEmail());
        assertEquals(before.getUsername(), after.getUsername());
        assertEquals(before.getAccountStatus(), after.getAccountStatus());
        assertEquals(before.getEmailVerifiedAt(), after.getEmailVerifiedAt());

        String key = RedisRevocationKey.forUser(userId);
        String value = redisTemplate.opsForValue().get(key);
        assertEquals(Long.toString(cutoff.toEpochMilli()), value);
        assertEquals(-1L, redisTemplate.getExpire(key, TimeUnit.SECONDS));
        assertFalse(key.contains("@example.com"));
        assertFalse(value.contains("@example.com"));
        assertTrue(value.matches("-?\\d+"));
    }

    @Test
    @Order(3)
    void authoritativeCutoffNeverMovesBackward() {
        UUID userId = persistAccount();
        Instant newer = Instant.parse("2026-08-02T03:04:06Z");
        Instant older = Instant.parse("2026-08-02T03:04:05Z");

        revocationService.recordRevocation(userId, newer);
        revocationService.recordRevocation(userId, older);

        assertEquals(newer, revocationService.find(userId).orElseThrow().revokedBefore());
        assertEquals(newer, redisStateStore.lookup(userId).revokedBefore());
        assertThrows(
                IllegalArgumentException.class, () -> revocationService.recordRevocation(UUID.randomUUID(), newer));
    }

    @Test
    @Order(4)
    void concurrentAuthoritativeUpdatesConvergeToTheGreatestCutoff() throws Exception {
        UUID userId = persistAccount();
        List<Instant> cutoffs = List.of(
                Instant.parse("2026-08-03T03:04:01Z"),
                Instant.parse("2026-08-03T03:04:02Z"),
                Instant.parse("2026-08-03T03:04:03Z"));
        CountDownLatch ready = new CountDownLatch(cutoffs.size());
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(cutoffs.size());

        try {
            List<Future<RevocationWriteResult>> futures = new ArrayList<>();
            for (Instant cutoff : cutoffs) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(release);
                    return revocationService.recordRevocation(userId, cutoff);
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            release.countDown();
            for (Future<RevocationWriteResult> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        Instant greatest = cutoffs.stream().max(Instant::compareTo).orElseThrow();
        assertEquals(greatest, revocationService.find(userId).orElseThrow().revokedBefore());
        assertEquals(greatest, redisStateStore.lookup(userId).revokedBefore());
    }

    @Test
    @Order(5)
    void republishReconstructsRedisStateFromPostgres() {
        UUID userId = persistAccount();
        Instant cutoff = Instant.parse("2026-08-04T03:04:05Z");
        revocationService.recordRevocation(userId, cutoff);
        redisTemplate.delete(RedisRevocationKey.forUser(userId));

        assertEquals(
                RedisRevocationLookupStatus.NOT_PRESENT,
                redisStateStore.lookup(userId).status());

        RevocationRepublishResult result = revocationService.republish(userId);

        assertEquals(cutoff, result.authoritativeState().orElseThrow().revokedBefore());
        assertEquals(RedisRevocationPropagationStatus.PROPAGATED, result.propagationStatus());
        assertEquals(cutoff, redisStateStore.lookup(userId).revokedBefore());
        RevocationRepublishResult absent = revocationService.republish(UUID.randomUUID());
        assertTrue(absent.authoritativeState().isEmpty());
        assertEquals(RedisRevocationPropagationStatus.NOT_APPLICABLE, absent.propagationStatus());
    }

    @Test
    @Order(6)
    void staleRedisWriteCannotLowerTheDerivedCutoff() {
        UUID userId = persistAccount();
        Instant newer = Instant.parse("2026-08-05T03:04:06Z");
        Instant older = Instant.parse("2026-08-05T03:04:05Z");
        revocationService.recordRevocation(userId, newer);

        RedisRevocationPropagationStatus status = redisStateStore
                .publish(new AuthoritativeRevocationState(userId, older))
                .status();

        assertEquals(RedisRevocationPropagationStatus.PROPAGATED, status);
        assertEquals(newer, redisStateStore.lookup(userId).revokedBefore());
    }

    @Test
    @Order(99)
    void redisUnavailableIsReportedWithoutRollingBackPostgres() {
        UUID userId = persistAccount();
        Instant cutoff = Instant.parse("2026-08-06T03:04:05Z");
        REDIS.stop();

        RevocationWriteResult result = revocationService.recordRevocation(userId, cutoff);

        assertEquals(cutoff, result.authoritativeState().revokedBefore());
        assertEquals(RedisRevocationPropagationStatus.UNAVAILABLE, result.propagationStatus());
        assertEquals(cutoff, revocationService.find(userId).orElseThrow().revokedBefore());
        assertEquals(
                RedisRevocationLookupStatus.UNAVAILABLE,
                redisStateStore.lookup(userId).status());
    }

    private UUID persistAccount() {
        UserAccountEntity account = userAccountRepository.saveAndFlush(
                new UserAccountEntity("revocation-" + UUID.randomUUID() + "@example.com", null));
        return account.getId();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent revocation workers");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent revocation worker interrupted", exception);
        }
    }
}
